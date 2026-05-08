package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ReportService;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class StaleReportCleanupJob extends Job {

    private static final Lock JOB_LOCK = new Lock("stale_report_cleanup:lock");

    private final @NonNull ReportService reportService;
    private final @NonNull LockService lockService;

    @Override
    public void doJob(JobExecutionContext context) {
        lockService.bestEffortLock(
                JOB_LOCK,
                Mono.fromRunnable(reportService::failStaleReports),
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
