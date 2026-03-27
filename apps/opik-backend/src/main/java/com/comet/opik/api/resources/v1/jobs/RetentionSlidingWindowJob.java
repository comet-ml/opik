package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.retention.RetentionPolicyService;
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
import java.time.ZoneOffset;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Quartz job for the regular sliding-window retention cycle.
 * Runs every (24*60)/executionsPerDay minutes, processing one workspace fraction per tick.
 * Uses distributed locking (holdUntilExpiry) to prevent concurrent execution across instances.
 * Concurrency is guarded by the Redis lock, not by Quartz — doJob() returns immediately
 * and the reactive chain runs in the background. Retention deletes are idempotent, so
 * incomplete work during shutdown is safely retried on the next cycle.
 */
@Singleton
@Slf4j
public class RetentionSlidingWindowJob extends Job {

    private static final Lock RUN_LOCK = new Lock("retention_policy:sliding_window_lock");

    private final RetentionPolicyService retentionPolicyService;
    private final LockService lockService;
    private final RetentionConfig config;

    @Inject
    public RetentionSlidingWindowJob(
            @NonNull RetentionPolicyService retentionPolicyService,
            @NonNull LockService lockService,
            @NonNull @Config("retention") RetentionConfig config) {
        this.retentionPolicyService = retentionPolicyService;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        Instant now = Instant.now();
        int fraction = computeCurrentFraction(now);

        lockService.bestEffortLock(
                RUN_LOCK,
                retentionPolicyService.executeRetentionCycle(fraction, now),
                Mono.fromRunnable(() -> log.info(
                        "Retention sliding window: could not acquire lock, another instance is running")),
                config.getInterval(),
                Duration.ZERO,
                true) // holdUntilExpiry: prevent redundant runs across instances
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Retention sliding window tick failed", error),
                        () -> log.debug("Retention sliding window tick completed: fraction='{}'", fraction));
    }

    int computeCurrentFraction(Instant now) {
        int minuteOfDay = now.atZone(ZoneOffset.UTC).getHour() * 60
                + now.atZone(ZoneOffset.UTC).getMinute();
        int intervalMinutes = (int) config.getInterval().toMinutes();
        return (minuteOfDay / intervalMinutes) % config.getTotalFractions();
    }
}
