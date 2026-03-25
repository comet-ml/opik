package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.JobTimeoutConfig;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class TraceThreadsClosingJob extends Job implements InterruptableJob {

    // On first run after startup (or after an outage), look back further to catch any threads that
    // became stale during downtime. This is deliberately conservative — the minmax skip index keeps
    // the cost proportional to matching granules, so the wider window is still very cheap.

    private final TraceThreadService traceThreadService;
    private final LockService lockService;
    private final TraceThreadConfig traceThreadConfig;
    private final RedissonReactiveClient redisClient;
    private final JobTimeoutConfig jobTimeoutConfig;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicBoolean completedFirstRun = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong backoffUntilMillis = new AtomicLong(0);

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
            log.info("Trace threads closing job interrupted before execution, skipping");
            return;
        }

        // Skip execution if still in backoff period
        long backoffUntil = backoffUntilMillis.get();
        if (backoffUntil > 0 && System.currentTimeMillis() < backoffUntil) {
            log.info("Trace threads closing job in backoff period (consecutive failures: '{}'), skipping execution",
                    consecutiveFailures.get());
            return;
        }

        var lock = new Lock("job", TraceThreadsClosingJob.class.getSimpleName());
        var defaultTimeoutToMarkThreadAsInactive = traceThreadConfig
                .getTimeoutToMarkThreadAsInactive().toJavaDuration(); // This is the default timeout to mark threads as inactive when workspace config is not set
        int limit = traceThreadConfig.getCloseTraceThreadMaxItemPerRun(); // Limit to a process in each job execution

        lockAndProcessJob(lock, defaultTimeoutToMarkThreadAsInactive, limit)
                .timeout(Duration.ofSeconds(jobTimeoutConfig.getTraceThreadsClosingJobTimeout())) // Add timeout to prevent hanging
                .subscribe(
                        __ -> {
                            // Note: Mono<Void> never emits onNext, success is handled in onComplete below
                        },
                        error -> {
                            if (interrupted.get()) {
                                log.warn("Closing of trace threads was interrupted", error);
                            } else {
                                applyBackoff();
                                log.error("Error processing closing of trace threads (consecutive failures: '{}')",
                                        consecutiveFailures.get(), error);
                            }
                        },
                        () -> {
                            if (!interrupted.get()) {
                                completedFirstRun.set(true);
                                consecutiveFailures.set(0);
                                backoffUntilMillis.set(0);
                                log.info("Successfully completed closing trace threads process");
                            } else {
                                log.info(
                                        "Closing trace threads process completed but was interrupted during execution");
                            }
                        });
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Successfully called closing trace threads job interruption");
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
                    var minLookback = completedFirstRun.get()
                            ? null
                            : traceThreadConfig.getColdStartLookback().toJavaDuration();
                    return enqueueInRedis(
                            traceThreadService
                                    .getProjectsWithPendingClosureThreads(
                                            now,
                                            defaultTimeoutToMarkThreadAsInactive,
                                            minLookback,
                                            limit));
                }),
                Mono.fromCallable(() -> {
                    log.info("Could not acquire lock for trace threads closing job, skipping execution");
                    return null;
                }),
                traceThreadConfig.getCloseTraceThreadJobInterval().toJavaDuration(), // Lock TTL = interval, held until expiry to prevent other pods from running
                traceThreadConfig.getCloseTraceThreadJobLockWaitTime().toJavaDuration(), // Timeout to acquiring the lock
                true); // holdUntilExpiry — don't release lock on completion, let TTL expire naturally
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
                                    return stream.add(RedisStreamUtils.buildAddArgs(
                                            TraceThreadConfig.PAYLOAD_FIELD, message, traceThreadConfig));
                                } else {
                                    log.info("Project {} is already in the pending closure list, skipping enqueue",
                                            message.projectId());
                                    return Mono.empty();
                                }
                            });
                })
                .doOnError(this::errorLog)
                .doOnComplete(() -> {
                    if (interrupted.get()) {
                        log.info("Closing trace threads process interrupted");
                    } else {
                        log.debug("Messages enqueued successfully in stream {}", traceThreadConfig.getStreamName());
                    }
                })
                .then();
    }

    private void applyBackoff() {
        int failures = consecutiveFailures.incrementAndGet();
        int exponent = Math.min(failures, traceThreadConfig.getMaxBackoffExponent());
        long intervalMs = traceThreadConfig.getCloseTraceThreadJobInterval().toJavaDuration().toMillis();
        long backoffMs = intervalMs * (1L << exponent);
        backoffUntilMillis.set(System.currentTimeMillis() + backoffMs);
        log.warn("Applying exponential backoff: next execution in '{}' ms (failure #'{}')", backoffMs, failures);
    }

    private void errorLog(Throwable throwable) {
        log.error("Error sending message", throwable);
    }
}
