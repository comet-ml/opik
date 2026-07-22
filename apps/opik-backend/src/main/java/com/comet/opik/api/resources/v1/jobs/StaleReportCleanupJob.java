package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ReportService;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Slf4j
@Singleton
@DisallowConcurrentExecution
@Every("15min")
public class StaleReportCleanupJob extends Job {

    private static final Lock JOB_LOCK = new Lock("stale_report_cleanup:lock");

    private final ReportService reportService;
    private final LockService lockService;

    private final LongCounter staleReportsCounter;

    @Inject
    public StaleReportCleanupJob(
            @NonNull ReportService reportService,
            @NonNull LockService lockService) {
        this.reportService = reportService;
        this.lockService = lockService;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.daily_report");

        this.staleReportsCounter = meter
                .counterBuilder("opik.daily_report.stale_swept")
                .setDescription("Number of stale reports marked as failed")
                .build();
    }

    @Override
    public void doJob(JobExecutionContext context) {
        lockService.bestEffortLock(
                JOB_LOCK,
                Mono.fromRunnable(() -> {
                    int swept = reportService.failStaleReports();
                    log.info("Stale report cleanup swept {} reports", swept);
                    if (swept > 0) {
                        staleReportsCounter.add(swept);
                    }
                }),
                Mono.defer(() -> {
                    log.debug("Could not acquire lock for stale report cleanup, another instance is running");
                    return Mono.empty();
                }),
                Duration.ofMinutes(1),
                Duration.ZERO,
                true).subscribe(
                        __ -> {
                        },
                        error -> log.error("Stale report cleanup failed", error));
    }
}
