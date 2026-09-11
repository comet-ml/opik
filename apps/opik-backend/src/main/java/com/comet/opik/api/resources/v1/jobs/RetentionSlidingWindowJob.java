package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.retention.RetentionPolicyService;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Quartz job for the regular sliding-window retention cycle.
 * Runs every (24*60)/executionsPerDay minutes, processing one workspace fraction per tick.
 * Uses distributed locking (holdUntilExpiry) to prevent concurrent execution across instances.
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
public class RetentionSlidingWindowJob extends Job implements InterruptableJob {

    private static final Lock RUN_LOCK = new Lock("retention_policy:sliding_window_lock");

    private final RetentionPolicyService retentionPolicyService;
    private final LockService lockService;
    private final RetentionConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    private final LongCounter runCounter;
    private final LongHistogram runDuration;

    @Inject
    public RetentionSlidingWindowJob(
            @NonNull RetentionPolicyService retentionPolicyService,
            @NonNull LockService lockService,
            @NonNull @Config("retention") RetentionConfig config) {
        this.retentionPolicyService = retentionPolicyService;
        this.lockService = lockService;
        this.config = config;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.retention");

        this.runCounter = meter
                .counterBuilder("opik.retention.sliding_window.run")
                .setDescription("Number of sliding window retention job runs")
                .build();

        this.runDuration = meter
                .histogramBuilder("opik.retention.sliding_window.duration")
                .setDescription("Duration of sliding window retention job runs")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("Retention sliding window job interrupted before execution, skipping");
            return;
        }

        Instant now = Instant.now();
        int fraction = computeCurrentFraction(now);
        long startMs = System.currentTimeMillis();

        try {
            lockService.bestEffortLock(
                    RUN_LOCK,
                    retentionPolicyService.executeRetentionCycle(fraction, now),
                    Mono.fromRunnable(() -> {
                        log.debug("Retention sliding window: could not acquire lock, another instance is running");
                        runCounter.add(1, Attributes.of(stringKey("result"), "skipped_lock"));
                    }),
                    config.getInterval(),
                    Duration.ZERO,
                    true) // holdUntilExpiry: prevent redundant runs across instances
                    .block();
            log.debug("Retention sliding window tick completed: fraction='{}'", fraction);
            runCounter.add(1, Attributes.of(
                    stringKey("result"), "success",
                    longKey("fraction"), (long) fraction));
            runDuration.record(System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            log.error("Retention sliding window tick failed", e);
            runCounter.add(1, Attributes.of(stringKey("result"), "error"));
            runDuration.record(System.currentTimeMillis() - startMs);
        }
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Retention sliding window job interrupted");
    }

    int computeCurrentFraction(Instant now) {
        int minuteOfDay = now.atZone(ZoneOffset.UTC).getHour() * 60
                + now.atZone(ZoneOffset.UTC).getMinute();
        int intervalMinutes = (int) config.getInterval().toMinutes();
        return (minuteOfDay / intervalMinutes) % config.getTotalFractions();
    }
}
