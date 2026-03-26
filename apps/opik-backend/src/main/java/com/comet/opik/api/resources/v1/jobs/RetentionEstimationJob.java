package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.retention.RetentionEstimationService;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Quartz job for estimating velocity of newly created retention rules.
 * Runs frequently (every 5 min) but is a fast no-op when no rules need estimation.
 * Separated from the HTTP request handler to avoid blocking the request thread
 * with potentially slow ClickHouse queries (especially month-by-month scouting
 * for huge workspaces).
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
public class RetentionEstimationJob extends Job implements InterruptableJob {

    private static final Lock RUN_LOCK = new Lock("retention_policy:estimation_lock");

    private final RetentionEstimationService estimationService;
    private final LockService lockService;
    private final RetentionConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    @Inject
    public RetentionEstimationJob(
            @NonNull RetentionEstimationService estimationService,
            @NonNull LockService lockService,
            @NonNull @Config("retention") RetentionConfig config) {
        this.estimationService = estimationService;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("Retention estimation job interrupted before execution, skipping");
            return;
        }

        lockService.bestEffortLock(
                RUN_LOCK,
                Mono.fromRunnable(() -> {
                    if (!interrupted.get()) {
                        estimationService.estimatePendingRules();
                    }
                }),
                Mono.fromRunnable(() -> log.debug(
                        "Retention estimation: could not acquire lock, another instance is running")),
                Duration.ofMinutes(config.getCatchUp().getEstimationIntervalMinutes()),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Retention estimation tick failed", error),
                        () -> log.debug("Retention estimation tick completed"));
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Retention estimation job interrupted");
    }
}
