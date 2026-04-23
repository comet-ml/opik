package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ExperimentProjectMigrationService;
import com.comet.opik.infrastructure.ExperimentProjectMigrationConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.domain.ExperimentProjectMigrationService.METRIC_NAMESPACE;
import static com.comet.opik.domain.ExperimentProjectMigrationService.RESULT_ERROR;
import static com.comet.opik.domain.ExperimentProjectMigrationService.RESULT_KEY;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class ExperimentProjectMigrationJob extends Job implements InterruptableJob {

    private static final Lock JOB_LOCK = new Lock("opik_job", ExperimentProjectMigrationJob.class.getSimpleName());

    private static final Attributes RESULT_SUCCESS = Attributes.of(RESULT_KEY, "success");
    private static final Attributes RESULT_LOCK_SKIPPED = Attributes.of(RESULT_KEY, "lock_skipped");

    private final ExperimentProjectMigrationService migrationService;
    private final ExperimentProjectMigrationConfig config;
    private final LockService lockService;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    private final LongHistogram cycleDuration;

    @Inject
    public ExperimentProjectMigrationJob(
            @NonNull ExperimentProjectMigrationService migrationService,
            @NonNull @Config("experimentProjectMigration") ExperimentProjectMigrationConfig config,
            @NonNull LockService lockService) {
        this.migrationService = migrationService;
        this.config = config;
        this.lockService = lockService;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleDuration = meter
                .histogramBuilder("%s.cycle.duration".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Duration of an experiment project migration cycle, tagged by result")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.enabled()) {
            log.debug("Experiment project migration job is disabled, skipping");
            return;
        }
        if (interrupted.get()) {
            log.info("Experiment project migration job was interrupted before execution, skipping");
            return;
        }
        log.info("Starting experiment project migration job");
        var startMillis = System.currentTimeMillis();
        var subscription = lockService.bestEffortLock(
                JOB_LOCK,
                Mono.defer(() -> {
                    if (interrupted.get()) {
                        log.info("Experiment project migration was interrupted before processing, skipping");
                        return Mono.empty();
                    }
                    return migrationService.runMigrationCycle()
                            .timeout(config.jobTimeout().toJavaDuration())
                            .doOnSuccess(unused -> cycleDuration.record(
                                    System.currentTimeMillis() - startMillis, RESULT_SUCCESS));
                }),
                Mono.defer(() -> {
                    log.info("Could not acquire lock, another instance is already running");
                    cycleDuration.record(System.currentTimeMillis() - startMillis, RESULT_LOCK_SKIPPED);
                    return Mono.empty();
                }),
                config.lockTimeout().toJavaDuration(),
                config.lockWaitTime().toJavaDuration(),
                true)
                // Resume on errors so the recurring job stays alive
                .onErrorResume(throwable -> {
                    cycleDuration.record(System.currentTimeMillis() - startMillis, RESULT_ERROR);
                    if (interrupted.get()) {
                        log.warn("Experiment project migration was interrupted", throwable);
                    } else {
                        log.error("Experiment project migration failed", throwable);
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
        log.info("Interrupting experiment project migration job");
        interrupted.set(true);
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
            log.info("Experiment project migration job interrupted successfully");
        }
    }
}
