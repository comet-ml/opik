package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.retention.RetentionCatchUpService;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Quartz job for progressive historical data deletion (catch-up).
 * Runs on a separate schedule from the sliding window job, with its own lock,
 * so catch-up work never blocks regular retention.
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
public class RetentionCatchUpJob extends Job implements InterruptableJob {

    private static final Lock RUN_LOCK = new Lock("retention_policy:catch_up_lock");

    private final RetentionCatchUpService catchUpService;
    private final LockService lockService;
    private final RetentionConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    private final LongCounter runCounter;
    private final LongHistogram runDuration;

    @Inject
    public RetentionCatchUpJob(
            @NonNull RetentionCatchUpService catchUpService,
            @NonNull LockService lockService,
            @NonNull @Config("retention") RetentionConfig config) {
        this.catchUpService = catchUpService;
        this.lockService = lockService;
        this.config = config;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.retention");

        this.runCounter = meter
                .counterBuilder("opik.retention.catch_up.run")
                .setDescription("Number of catch-up retention job runs")
                .build();

        this.runDuration = meter
                .histogramBuilder("opik.retention.catch_up.duration")
                .setDescription("Duration of catch-up retention job runs")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("Retention catch-up job interrupted before execution, skipping");
            return;
        }

        long startMs = System.currentTimeMillis();

        try {
            lockService.bestEffortLock(
                    RUN_LOCK,
                    catchUpService.executeCatchUpCycle(Instant.now()),
                    Mono.fromRunnable(() -> {
                        log.debug("Retention catch-up: could not acquire lock, another instance is running");
                        runCounter.add(1, Attributes.of(stringKey("result"), "skipped_lock"));
                    }),
                    config.getCatchUp().getCatchUpInterval(),
                    Duration.ZERO,
                    true) // holdUntilExpiry: prevent redundant runs across instances
                    .block();
            log.debug("Retention catch-up tick completed");
            runCounter.add(1, Attributes.of(stringKey("result"), "success"));
            runDuration.record(System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            log.error("Retention catch-up tick failed", e);
            runCounter.add(1, Attributes.of(stringKey("result"), "error"));
            runDuration.record(System.currentTimeMillis() - startMs);
        }
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Retention catch-up job interrupted");
    }
}
