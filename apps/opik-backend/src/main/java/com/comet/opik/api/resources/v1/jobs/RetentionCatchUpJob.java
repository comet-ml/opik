package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.retention.RetentionCatchUpService;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Quartz job for progressive historical data deletion (catch-up).
 * Runs on a separate schedule from the sliding window job, with its own lock,
 * so catch-up work never blocks regular retention.
 * Concurrency is guarded by the Redis lock, not by Quartz — doJob() returns immediately
 * and the reactive chain runs in the background. Retention deletes are idempotent, so
 * incomplete work during shutdown is safely retried on the next cycle.
 */
@Singleton
@Slf4j
public class RetentionCatchUpJob extends Job {

    private static final Lock RUN_LOCK = new Lock("retention_policy:catch_up_lock");

    private final RetentionCatchUpService catchUpService;
    private final LockService lockService;
    private final RetentionConfig config;

    @Inject
    public RetentionCatchUpJob(
            @NonNull RetentionCatchUpService catchUpService,
            @NonNull LockService lockService,
            @NonNull @Config("retention") RetentionConfig config) {
        this.catchUpService = catchUpService;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        lockService.bestEffortLock(
                RUN_LOCK,
                catchUpService.executeCatchUpCycle(Instant.now()),
                Mono.fromRunnable(() -> log.info(
                        "Retention catch-up: could not acquire lock, another instance is running")),
                config.getCatchUp().getInterval(),
                Duration.ZERO,
                true) // holdUntilExpiry: prevent redundant runs across instances
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Retention catch-up tick failed", error),
                        () -> log.debug("Retention catch-up tick completed"));
    }
}
