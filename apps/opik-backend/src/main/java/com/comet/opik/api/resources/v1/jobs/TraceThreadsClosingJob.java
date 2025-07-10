package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
public class TraceThreadsClosingJob extends Job {

    private final TraceThreadService traceThreadService;
    private final LockService lockService;
    private final TraceThreadConfig traceThreadConfig;
    private final RedissonReactiveClient redisClient;

    @Inject
    public TraceThreadsClosingJob(@NonNull TraceThreadService traceThreadService,
            @NonNull LockService lockService,
            @NonNull @Config TraceThreadConfig traceThreadConfig,
            @NonNull RedissonReactiveClient redisClient) {
        this.traceThreadService = traceThreadService;
        this.lockService = lockService;
        this.traceThreadConfig = traceThreadConfig;
        this.redisClient = redisClient;
    }

    @Override
    public void doJob(JobExecutionContext jobExecutionContext) {
        var lock = new Lock("job", TraceThreadsClosingJob.class.getSimpleName());
        var timeoutToMarkThreadAsInactive = traceThreadConfig
                .getTimeoutToMarkThreadAsInactive().toJavaDuration(); // This is the timeout to mark threads as inactive
        int limit = traceThreadConfig.getCloseTraceThreadMaxItemPerRun(); // Limit to a process in each job execution

        lockAndProcessJob(lock, timeoutToMarkThreadAsInactive, limit)
                .subscribe(
                        __ -> log.info("Successfully started closing trace threads process"),
                        error -> log.error("Error processing closing of trace threads", error));
    }

    private Mono<Void> lockAndProcessJob(Lock lock, Duration timeoutToMarkThreadAsInactive, int limit) {
        return lockService.bestEffortLock(
                lock,
                Mono.defer(() -> {
                    var now = Instant.now();
                    return enqueueInRedis(
                            traceThreadService
                                    .getProjectsWithPendingClosureThreads(
                                            now,
                                            timeoutToMarkThreadAsInactive,
                                            limit));
                }),
                Mono.fromCallable(() -> {
                    log.info("Could not acquire lock for TraceThreadsClosingJob, skipping execution");
                    return null;
                }),
                traceThreadConfig.getCloseTraceThreadJobLockTime().toJavaDuration(), // Timeout to release the lock
                traceThreadConfig.getCloseTraceThreadJobLockWaitTime().toJavaDuration()); // Timeout to acquiring the lock
    }

    private Mono<Void> enqueueInRedis(Flux<ProjectWithPendingClosureTraceThreads> flux) {
        var stream = redisClient.getStream(traceThreadConfig.getStreamName(), traceThreadConfig.getCodec());

        return flux.flatMap(message -> traceThreadService.addToPendingQueue(message.projectId())
                .flatMap(pending -> {
                    if (Boolean.TRUE.equals(pending)) {
                        return stream.add(StreamAddArgs.entry(TraceThreadConfig.PAYLOAD_FIELD, message));
                    } else {
                        log.info("Project {} is already in the pending closure list, skipping enqueue",
                                message.projectId());
                        return Mono.empty();
                    }
                }))
                .doOnError(this::errorLog)
                .collectList()
                .doOnSuccess(ids -> {
                    if (ids.isEmpty()) {
                        log.info("No messages to enqueue in stream {}", traceThreadConfig.getStreamName());
                    } else {
                        log.info("A total of '{}' messages enqueued successfully in stream {}", ids.size(),
                                traceThreadConfig.getStreamName());
                    }
                })
                .then();
    }

    private void errorLog(Throwable throwable) {
        log.error("Error sending message", throwable);
    }
}
