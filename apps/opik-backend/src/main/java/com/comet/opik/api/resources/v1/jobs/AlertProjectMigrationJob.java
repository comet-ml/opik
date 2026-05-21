package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.AlertProjectMigrationService;
import com.comet.opik.infrastructure.AlertProjectMigrationConfig;
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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.domain.AlertProjectMigrationService.METRIC_NAMESPACE;
import static com.comet.opik.domain.AlertProjectMigrationService.RESULT_ERROR;
import static com.comet.opik.domain.AlertProjectMigrationService.RESULT_KEY;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class AlertProjectMigrationJob extends Job implements InterruptableJob {

    private static final Lock JOB_LOCK = new Lock("opik_job", AlertProjectMigrationJob.class.getSimpleName());

    private static final Attributes RESULT_SUCCESS = Attributes.of(RESULT_KEY, "success");
    private static final Attributes RESULT_LOCK_SKIPPED = Attributes.of(RESULT_KEY, "lock_skipped");

    private final AlertProjectMigrationService migrationService;
    private final AlertProjectMigrationConfig config;
    private final LockService lockService;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    private final LongHistogram cycleDuration;

    @Inject
    public AlertProjectMigrationJob(
            @NonNull AlertProjectMigrationService migrationService,
            @NonNull @Config("alertProjectMigration") AlertProjectMigrationConfig config,
            @NonNull LockService lockService) {
        this.migrationService = migrationService;
        this.config = config;
        this.lockService = lockService;

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleDuration = meter
                .histogramBuilder("%s.cycle.duration".formatted(METRIC_NAMESPACE))
                .setDescription("Duration of an alert project migration cycle, tagged by result")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.enabled()) {
            log.info("Alert project migration job is disabled, skipping");
            return;
        }
        if (interrupted.get()) {
            log.info("Alert project migration job was interrupted before execution, skipping");
            return;
        }
        log.info("Starting alert project migration job");
        var startMillis = System.currentTimeMillis();
        var subscription = lockService.bestEffortLock(
                JOB_LOCK,
                Mono.defer(() -> {
                    if (interrupted.get()) {
                        log.info("Alert project migration was interrupted before processing, skipping");
                        return Mono.empty();
                    }
                    return migrationService.runMigrationCycle()
                            .timeout(config.jobTimeout().toJavaDuration())
                            .doOnSuccess(unused -> {
                                long durationMillis = System.currentTimeMillis() - startMillis;
                                var duration = Duration.ofMillis(durationMillis);
                                cycleDuration.record(durationMillis, RESULT_SUCCESS);
                                log.info("Alert project migration cycle finished, duration='{}'", duration);
                            });
                }),
                Mono.defer(() -> {
                    long durationMillis = System.currentTimeMillis() - startMillis;
                    var duration = Duration.ofMillis(durationMillis);
                    cycleDuration.record(durationMillis, RESULT_LOCK_SKIPPED);
                    log.info("Could not acquire lock, another instance is already running, duration='{}'", duration);
                    return Mono.empty();
                }),
                config.lockTimeout().toJavaDuration(),
                config.lockWaitTime().toJavaDuration(),
                false)
                .onErrorResume(throwable -> {
                    long durationMillis = System.currentTimeMillis() - startMillis;
                    var duration = Duration.ofMillis(durationMillis);
                    cycleDuration.record(durationMillis, RESULT_ERROR);
                    if (interrupted.get()) {
                        log.warn("Alert project migration was interrupted, duration='{}'", duration, throwable);
                    } else {
                        log.error("Alert project migration failed, duration='{}'", duration, throwable);
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
        log.info("Interrupting alert project migration job");
        interrupted.set(true);
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
            log.info("Alert project migration job interrupted successfully");
        }
    }
}
