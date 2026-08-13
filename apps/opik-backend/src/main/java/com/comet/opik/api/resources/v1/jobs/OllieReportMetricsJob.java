package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ReportService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;

/**
 * Refreshes the snapshot behind the {@code opik.daily_report.pending} gauge, keeping the database read off
 * the OTel collection callback so a slow query cannot stall the metric reader.
 *
 * <p>Every replica keeps its own snapshot and reports the same database-wide count, so no lock is needed;
 * the dashboard deduplicates with {@code max by (workspace_id)}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@Every("1m")
public class OllieReportMetricsJob extends Job {

    private final ReportService reportService;

    @Inject
    public OllieReportMetricsJob(@NonNull ReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        reportService.refreshPendingReports();
    }
}
