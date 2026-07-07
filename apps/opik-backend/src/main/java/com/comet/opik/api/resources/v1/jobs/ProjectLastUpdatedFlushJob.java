package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ProjectLastUpdatedTraceBufferService;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.UnableToInterruptJobException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Schedules the periodic drain of the Redis-buffered {@code projects.last_updated_trace_at} maxima to MySQL.
 *
 * <p>This job only orchestrates: it runs on the configured interval (default 30s,
 * {@code projectLastUpdatedFlush.jobInterval}) under a best-effort distributed lock held until expiry (so a single
 * instance flushes per cycle across the cluster) and handles interruption. All the flush business logic — including
 * Redis access — lives in {@link ProjectLastUpdatedTraceBufferService#flush()}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class ProjectLastUpdatedFlushJob extends Job implements InterruptableJob {

    private static final Lock FLUSH_LOCK = new Lock("project_last_updated_flush_job:scan_lock");

    private static final AttributeKey<String> RESULT_KEY = stringKey("result");

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final ProjectLastUpdatedFlushConfig config;
    private final ProjectLastUpdatedTraceBufferService bufferService;
    private final LockService lockService;

    private final LongCounter runCounter;
    private final LongHistogram runDuration;

    @Inject
    public ProjectLastUpdatedFlushJob(
            @NonNull @Config("projectLastUpdatedFlush") ProjectLastUpdatedFlushConfig config,
            @NonNull ProjectLastUpdatedTraceBufferService bufferService,
            @NonNull LockService lockService) {
        this.config = config;
        this.bufferService = bufferService;
        this.lockService = lockService;

        var meter = GlobalOpenTelemetry.get().getMeter("opik.project_last_updated_flush");
        this.runCounter = meter
                .counterBuilder("opik.project_last_updated_flush.run")
                .setDescription("Project last-updated flush job runs, by result")
                .build();
        this.runDuration = meter
                .histogramBuilder("opik.project_last_updated_flush.duration")
                .setDescription("Duration of a project last-updated flush job run")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || interrupted.get()) {
            return;
        }

        // flush() is blocking (Redis + MySQL); run it off the scheduler thread. The subscription is kept so interrupt()
        // can cancel an in-flight flush, not just the schedule.
        long startMs = System.currentTimeMillis();
        Disposable disposable = lockService.bestEffortLock(
                FLUSH_LOCK,
                Mono.fromCallable(bufferService::flush)
                        .subscribeOn(Schedulers.boundedElastic())
                        .doOnNext(count -> {
                            runCounter.add(1, Attributes.of(RESULT_KEY, "success"));
                            runDuration.record(System.currentTimeMillis() - startMs);
                            if (count > 0) {
                                log.info("Project last-updated flush processed '{}' project markers", count);
                            }
                        })
                        .doOnError(error -> {
                            runCounter.add(1, Attributes.of(RESULT_KEY, "error"));
                            runDuration.record(System.currentTimeMillis() - startMs);
                        })
                        .then(),
                Mono.fromRunnable(() -> {
                    runCounter.add(1, Attributes.of(RESULT_KEY, "skipped_lock"));
                    log.debug("Another instance is flushing project last-updated markers, skipping");
                }),
                config.getJobLockTime().toJavaDuration(),
                config.getJobLockWaitTime().toJavaDuration(),
                true)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Project last-updated flush job failed", error));
        subscription.set(disposable);
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        // Cancel an in-flight flush, not just the schedule.
        Optional.ofNullable(subscription.getAndSet(null)).ifPresent(Disposable::dispose);
        log.info("ProjectLastUpdatedFlushJob interrupted, cancelling any in-flight flush");
    }
}
