package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.OptimizationService;
import com.comet.opik.infrastructure.OptimizationStalledReaperConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Periodically transitions stalled Optimization Studio runs to {@code ERROR} (OPIK-7159).
 * <p>
 * A studio run's status is only ever advanced by the Python optimizer worker calling back to the API.
 * If the worker never runs (worker down, Redis/queue unreachable, job lost) or crashes before it can
 * report, the run is frozen on {@code INITIALIZED} (or {@code RUNNING}) forever with no error surfaced.
 * This reaper is the environment-independent safety net that guarantees a run can never stay stuck
 * indefinitely: it finds runs whose latest status is non-terminal and older than the configured
 * threshold and asks {@link OptimizationService#reconcileStalledStudioOptimizations} to mark them
 * failed with a clear reason. A distributed lock with hold-until-expiry keeps only one instance
 * reconciling per cycle.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class OptimizationStalledReaperJob extends Job implements InterruptableJob {

    private static final Lock JOB_LOCK = new Lock("optimization_stalled_reaper:lock");

    private final OptimizationService optimizationService;
    private final LockService lockService;
    private final OptimizationStalledReaperConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    /** Tracks the in-flight reactive pass so {@link #interrupt()} can dispose it. */
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    @Inject
    public OptimizationStalledReaperJob(@NonNull OptimizationService optimizationService,
            @NonNull LockService lockService,
            @NonNull @Config("optimizationStalledReaper") OptimizationStalledReaperConfig config) {
        this.optimizationService = optimizationService;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("Optimization stalled reaper job interrupted before execution, skipping");
            return;
        }

        var subscription = lockService.bestEffortLock(
                JOB_LOCK,
                Mono.defer(() -> {
                    if (interrupted.get()) {
                        log.info("Optimization stalled reaper interrupted before processing, skipping");
                        return Mono.empty();
                    }
                    return optimizationService.reconcileStalledStudioOptimizations(
                            config.initializedTimeout().toJavaDuration(),
                            config.runningTimeout().toJavaDuration(),
                            config.batchSize())
                            .doOnSuccess(count -> {
                                if (count > 0) {
                                    log.warn("Optimization stalled reaper marked '{}' stalled studio run(s) as ERROR",
                                            count);
                                } else {
                                    log.debug("Optimization stalled reaper found no stalled studio runs");
                                }
                            });
                }),
                Mono.fromRunnable(() -> log.debug(
                        "Could not acquire lock for optimization stalled reaper, another instance is running")),
                config.lockDuration().toJavaDuration(),
                Duration.ZERO,
                true) // holdUntilExpiry: prevent redundant runs across instances until the next cycle
                // Resume on errors so the recurring job stays alive.
                .onErrorResume(throwable -> {
                    if (interrupted.get()) {
                        log.warn("Optimization stalled reaper interrupted", throwable);
                    } else {
                        log.error("Optimization stalled reaper failed", throwable);
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
        interrupted.set(true);
        log.info("Optimization stalled reaper job interrupted");
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
            log.info("Optimization stalled reaper job interrupted successfully");
        }
    }
}
