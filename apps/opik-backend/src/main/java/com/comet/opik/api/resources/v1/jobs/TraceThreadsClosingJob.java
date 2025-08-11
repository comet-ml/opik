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
        // Check for interruption before starting
        if (interrupted.get()) {
            log.info("TraceThreadsClosingJob interrupted before execution, skipping");
            return;
        }

        var lock = new Lock("job", TraceThreadsClosingJob.class.getSimpleName());
        var defaultTimeoutToMarkThreadAsInactive = traceThreadConfig
                .getTimeoutToMarkThreadAsInactive().toJavaDuration(); // This is the default timeout to mark threads as inactive when workspace config is not set
        int limit = traceThreadConfig.getCloseTraceThreadMaxItemPerRun(); // Limit to a process in each job execution

        try {
            lockAndProcessJob(lock, defaultTimeoutToMarkThreadAsInactive, limit)
                    .timeout(Duration.ofSeconds(jobTimeoutConfig.getTraceThreadsClosingJobTimeout()))
                    .doOnSuccess(__ -> {
                        if (!interrupted.get()) {
                            log.info("Successfully started closing trace threads process");
                        } else {
                            log.info("TraceThreadsClosingJob completed but was interrupted during execution");
                        }
                    })
                    .doOnError(error -> {
                        if (interrupted.get()) {
                            log.warn("TraceThreadsClosingJob was interrupted", error);
                        } else {
                            log.error("Error processing closing of trace threads", error);
                        }
                    })
                    .block();
        } catch (Exception exception) {
            log.error("Failed to run trace threads closing job", exception);
        }
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("TraceThreadsClosingJob interruption completed");
    }

    private Mono<Void> lockAndProcessJob(Lock lock, Duration defaultTimeoutToMarkThreadAsInactive, int limit) {
        return lockService.bestEffortLock(
                lock,
                Mono.defer(() -> {
                    // Check for interruption before processing
                    if (interrupted.get()) {
                        log.info("TraceThreadsClosingJob interrupted before processing, skipping");
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
        int perOpTimeoutSec = 10;

        return flux.takeWhile(message -> !interrupted.get()) // Stop processing if interrupted
                .flatMap(message -> {
                    // Check for interruption before processing each message
                    if (interrupted.get()) {
                        log.info("TraceThreadsClosingJob interrupted during message processing, stopping");
                        return Mono.empty();
                    }

                    return traceThreadService.addToPendingQueue(message.projectId())
                            .timeout(Duration.ofSeconds(perOpTimeoutSec)).checkpoint("addToPendingQueue")
                            .flatMap(pending -> {
                                if (Boolean.TRUE.equals(pending)) {
                                    return stream.add(StreamAddArgs.entry(TraceThreadConfig.PAYLOAD_FIELD, message))
                                            .timeout(Duration.ofSeconds(perOpTimeoutSec))
                                            .checkpoint("redisStream.add")
                                            .then(Mono.empty()); // we don't need the id downstream
                                } else {
                                    log.info("Project {} is already in the pending closure list, skipping enqueue",
                                            message.projectId());
                                    return Mono.empty();
                                }
                            });
                }, /*concurrency*/ traceThreadConfig.getCloseTraceThreadMaxItemPerRun()) // optional cap
                .doOnError(this::errorLog)
                .then(); // no collectList() needed if you don't use the IDs
    }

    private void errorLog(Throwable throwable) {
        log.error("Error sending message", throwable);
    }
}
