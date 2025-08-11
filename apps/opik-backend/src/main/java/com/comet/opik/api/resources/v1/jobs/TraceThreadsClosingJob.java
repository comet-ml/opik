package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.JobTimeoutConfig;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class TraceThreadsClosingJob extends Job implements InterruptableJob {

    private final TraceThreadService traceThreadService;
    private final LockService lockService;
    private final TraceThreadConfig traceThreadConfig;
    private final RedissonReactiveClient redisClient;
    private final JobTimeoutConfig jobTimeoutConfig;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    @Inject
    public TraceThreadsClosingJob(@NonNull TraceThreadService traceThreadService,
            @NonNull LockService lockService,
            @NonNull @Config TraceThreadConfig traceThreadConfig,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("jobTimeout") JobTimeoutConfig jobTimeoutConfig) {
        this.traceThreadService = traceThreadService;
        this.lockService = lockService;
        this.traceThreadConfig = traceThreadConfig;
        this.redisClient = redisClient;
        this.jobTimeoutConfig = jobTimeoutConfig;
    }

    @Override
    public void doJob(JobExecutionContext jobExecutionContext) {
        log.info("Do nothing job, context '{}'", jobExecutionContext);
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Successfully called job interruption");
    }

    private Mono<Void> lockAndProcessJob(Lock lock, Duration defaultTimeoutToMarkThreadAsInactive, int limit) {
        return lockService.bestEffortLock(
                lock,
                Mono.defer(() -> {
                    // Check for interruption before processing
                    if (interrupted.get()) {
                        log.info("Closing trace threads process interrupted before processing, skipping");
                        return Mono.empty();
                    }

                    var now = Instant.now();
                    return enqueueInRedis(
                            traceThreadService
                                    .getProjectsWithPendingClosureThreads(
                                            now,
                                            defaultTimeoutToMarkThreadAsInactive,
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

        return flux.takeWhile(message -> !interrupted.get()) // Stop processing if interrupted
                .flatMap(message -> {
                    // Check for interruption before processing each message
                    if (interrupted.get()) {
                        log.info("Closing trace threads process interrupted during message processing, stopping");
                        return Mono.empty();
                    }

                    return traceThreadService.addToPendingQueue(message.projectId())
                            .flatMap(pending -> {
                                if (Boolean.TRUE.equals(pending)) {
                                    return stream.add(StreamAddArgs.entry(TraceThreadConfig.PAYLOAD_FIELD, message));
                                } else {
                                    log.info("Project {} is already in the pending closure list, skipping enqueue",
                                            message.projectId());
                                    return Mono.empty();
                                }
                            });
                })
                .doOnError(this::errorLog)
                .collectList()
                .doOnSuccess(ids -> {
                    if (interrupted.get()) {
                        log.info("Closing trace threads process interrupted, processed '{}' messages before stopping",
                                ids.size());
                    } else if (ids.isEmpty()) {
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
