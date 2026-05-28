package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Shared lock/metrics/error skeleton for the V1 → V2 project-migration Quartz jobs (experiments,
 * datasets, optimizations, prompts). Each entity-specific subclass keeps its
 * {@code @DisallowConcurrentExecution} annotation and supplies the entity label, metric namespace,
 * config, and the {@code runMigrationCycle()} delegate to its service.
 *
 * <p>The flow is identical for every subclass: respect the enabled flag, honour the interrupt
 * latch, take the best-effort distributed lock, run one cycle with the configured timeout, and
 * record a {@code result}-tagged histogram bucket (success / lock_skipped / error). Errors are
 * resumed so the recurring schedule stays alive.
 */
@Slf4j
public abstract class AbstractProjectMigrationJob extends Job implements InterruptableJob {

    private static final AttributeKey<String> RESULT_KEY = stringKey("result");

    private final LockService lockService;
    private final Lock jobLock;
    private final Attributes resultSuccess;
    private final Attributes resultLockSkipped;
    private final Attributes resultError;
    private final LongHistogram cycleDuration;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    protected AbstractProjectMigrationJob(@NonNull LockService lockService) {
        this.lockService = lockService;
        this.jobLock = new Lock("opik_job", getClass().getSimpleName());
        this.resultSuccess = Attributes.of(RESULT_KEY, "success");
        this.resultLockSkipped = Attributes.of(RESULT_KEY, "lock_skipped");
        this.resultError = Attributes.of(RESULT_KEY, "error");

        var meter = GlobalOpenTelemetry.get().getMeter(metricNamespace());
        this.cycleDuration = meter
                .histogramBuilder("%s.cycle.duration".formatted(metricNamespace()))
                .setDescription(
                        "Duration of a %s migration cycle, tagged by result".formatted(entityLabelLowerCase()))
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    /**
     * Entity-specific label used to log progress (e.g., {@code "Optimization project"}). Kept
     * capitalised because the subclass uses it directly in {@code "Starting %s migration job"}
     * style messages.
     */
    protected abstract String entityLabel();

    /**
     * Root metric namespace for the entity-specific service (e.g.,
     * {@code "opik.migration.optimization_project"}). The base class derives the cycle histogram
     * name from it.
     */
    protected abstract String metricNamespace();

    /** Configuration flag — short-circuits the cycle when the job is disabled. */
    protected abstract boolean isEnabled();

    /** Distributed-lock timeout — must be shorter than {@link #jobTimeout()}. */
    protected abstract Duration lockTimeout();

    /** Max time to wait for the distributed lock when another replica holds it. */
    protected abstract Duration lockWaitTime();

    /** Per-cycle hard timeout — fires if the migration cycle stalls. */
    protected abstract Duration jobTimeout();

    /**
     * Delegates to the entity-specific migration service's {@code runMigrationCycle()}. Subclasses
     * own the service instance; this hook stays thin so the abstract class doesn't need to know
     * the service type.
     */
    protected abstract Mono<Void> runMigrationCycle();

    @Override
    public void doJob(JobExecutionContext context) {
        if (!isEnabled()) {
            log.debug("{} migration job is disabled, skipping", entityLabel());
            return;
        }
        if (interrupted.get()) {
            log.info("{} migration job was interrupted before execution, skipping", entityLabel());
            return;
        }
        log.info("Starting {} migration job", entityLabelLowerCase());
        long startMillis = System.currentTimeMillis();
        var subscription = lockService.bestEffortLock(
                jobLock,
                Mono.defer(() -> {
                    if (interrupted.get()) {
                        log.info("{} migration was interrupted before processing, skipping", entityLabel());
                        return Mono.empty();
                    }
                    return runMigrationCycle()
                            .timeout(jobTimeout())
                            .doOnSuccess(unused -> {
                                long durationMillis = System.currentTimeMillis() - startMillis;
                                cycleDuration.record(durationMillis, resultSuccess);
                                log.info("{} migration cycle finished, duration='{}'",
                                        entityLabel(), Duration.ofMillis(durationMillis));
                            });
                }),
                Mono.defer(() -> {
                    long durationMillis = System.currentTimeMillis() - startMillis;
                    cycleDuration.record(durationMillis, resultLockSkipped);
                    log.info("Could not acquire lock, another instance is already running, duration='{}'",
                            Duration.ofMillis(durationMillis));
                    return Mono.empty();
                }),
                lockTimeout(),
                lockWaitTime(),
                false)
                // Resume on errors so the recurring job stays alive
                .onErrorResume(throwable -> {
                    long durationMillis = System.currentTimeMillis() - startMillis;
                    var duration = Duration.ofMillis(durationMillis);
                    cycleDuration.record(durationMillis, resultError);
                    if (interrupted.get()) {
                        log.warn("{} migration was interrupted, duration='{}'", entityLabel(), duration, throwable);
                    } else {
                        log.error("{} migration failed, duration='{}'", entityLabel(), duration, throwable);
                    }
                    return Mono.empty();
                })
                .doFinally(signal -> currentExecution.set(null))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        currentExecution.set(subscription);
    }

    @Override
    public void interrupt() {
        log.info("Interrupting {} migration job", entityLabelLowerCase());
        interrupted.set(true);
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
            log.info("{} migration job interrupted successfully", entityLabel());
        }
    }

    private String entityLabelLowerCase() {
        return entityLabel().toLowerCase();
    }
}
