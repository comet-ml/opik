package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.evaluators.AutomationRuleProjectMigrationService;
import com.comet.opik.infrastructure.AutomationRuleProjectMigrationConfig;
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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.domain.evaluators.AutomationRuleProjectMigrationService.METRIC_NAMESPACE;
import static com.comet.opik.domain.evaluators.AutomationRuleProjectMigrationService.RESULT_ERROR;
import static com.comet.opik.domain.evaluators.AutomationRuleProjectMigrationService.RESULT_KEY;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class AutomationRuleProjectMigrationJob extends Job implements InterruptableJob {

    private static final Lock JOB_LOCK = new Lock("opik_job",
            AutomationRuleProjectMigrationJob.class.getSimpleName());

    private static final Attributes RESULT_SUCCESS = Attributes.of(RESULT_KEY, "success");
    private static final Attributes RESULT_LOCK_SKIPPED = Attributes.of(RESULT_KEY, "lock_skipped");

    private final AutomationRuleProjectMigrationService migrationService;
    private final AutomationRuleProjectMigrationConfig config;
    private final LockService lockService;
    private final Scheduler migrationScheduler;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    private final LongHistogram cycleDuration;

    @Inject
    public AutomationRuleProjectMigrationJob(
            @NonNull AutomationRuleProjectMigrationService migrationService,
            @NonNull @Config("automationRuleProjectMigration") AutomationRuleProjectMigrationConfig config,
            @NonNull LockService lockService) {
        this.migrationService = migrationService;
        this.config = config;
        this.lockService = lockService;

        this.migrationScheduler = Schedulers.newBoundedElastic(
                config.schedulerThreadCap(),
                config.schedulerQueuedTaskCap(),
                "automation-rule-project-migration",
                (int) config.schedulerThreadTtl().toJavaDuration().toSeconds(),
                true);

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.cycleDuration = meter
                .histogramBuilder("%s.cycle.duration".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Duration of an automation rule project migration cycle, tagged by result")
                .setUnit("ms")
                .ofLongs()
                .build();

        log.info("Automation rule project migration job configured, enabled='{}', interval='{}', "
                + "workspacesPerRun='{}', maxRulesPerCycle='{}', lockTimeout='{}', "
                + "schedulerThreadCap='{}', schedulerQueuedTaskCap='{}'",
                config.enabled(), config.interval(), config.workspacesPerRun(),
                config.maxRulesPerCycle(), config.lockTimeout(),
                config.schedulerThreadCap(), config.schedulerQueuedTaskCap());
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.enabled()) {
            log.debug("Automation rule project migration job is disabled, skipping");
            return;
        }
        if (interrupted.get()) {
            log.info("Automation rule project migration job was interrupted before execution, skipping");
            return;
        }
        log.info("Starting automation rule project migration job");
        var startMillis = System.currentTimeMillis();
        var subscription = lockService.bestEffortLock(
                JOB_LOCK,
                Mono.defer(() -> {
                    if (interrupted.get()) {
                        log.info("Automation rule project migration was interrupted before processing, skipping");
                        return Mono.empty();
                    }
                    return Mono.<Void>fromRunnable(migrationService::runMigrationCycle)
                            .subscribeOn(migrationScheduler)
                            .timeout(config.jobTimeout().toJavaDuration())
                            .doOnSuccess(unused -> {
                                var durationMs = System.currentTimeMillis() - startMillis;
                                cycleDuration.record(durationMs, RESULT_SUCCESS);
                                log.info(
                                        "Automation rule project migration cycle completed successfully, durationMs='{}'",
                                        durationMs);
                            });
                }),
                Mono.defer(() -> {
                    log.info("Could not acquire lock, another instance is already running");
                    cycleDuration.record(System.currentTimeMillis() - startMillis, RESULT_LOCK_SKIPPED);
                    return Mono.empty();
                }),
                config.lockTimeout().toJavaDuration(),
                config.lockWaitTime().toJavaDuration(),
                true)
                .onErrorResume(throwable -> {
                    var durationMs = System.currentTimeMillis() - startMillis;
                    cycleDuration.record(durationMs, RESULT_ERROR);
                    if (interrupted.get()) {
                        log.warn("Automation rule project migration was interrupted, durationMs='{}'",
                                durationMs, throwable);
                    } else {
                        log.error("Automation rule project migration failed, durationMs='{}'",
                                durationMs, throwable);
                    }
                    return Mono.empty();
                })
                .doFinally(signal -> currentExecution.set(null))
                .subscribeOn(migrationScheduler)
                .subscribe();
        currentExecution.set(subscription);
    }

    @Override
    public void interrupt() {
        log.info("Interrupting automation rule project migration job");
        interrupted.set(true);
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
            log.info("Automation rule project migration job interrupted successfully");
        }
    }
}
