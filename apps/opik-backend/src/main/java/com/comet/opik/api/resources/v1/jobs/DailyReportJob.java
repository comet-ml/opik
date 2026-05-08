package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.ReportPreference;
import com.comet.opik.domain.ReportService;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.On;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Fires at 05:00 UTC, matching {@link ReportPreference#DEFAULT_SCHEDULE_TIME_UTC}.
 * When per-project schedule times are supported, replace with a frequent job that queries
 * enabled preferences and triggers only those whose schedule_time_utc falls within the current window.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@On(value = "0 0 5 * * ?", timeZone = "UTC")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DailyReportJob extends Job {

    private static final Lock JOB_LOCK = new Lock("daily_report_job:lock");

    private final @NonNull ReportService reportService;
    private final @NonNull LockService lockService;

    @Override
    public void doJob(JobExecutionContext context) {
        log.info("Starting daily report job");

        lockService.bestEffortLock(
                JOB_LOCK,
                Mono.fromRunnable(this::triggerReports),
                Mono.defer(() -> {
                    log.info("Could not acquire lock for daily report job, another instance is running");
                    return Mono.empty();
                }),
                Duration.ofMinutes(5),
                Duration.ZERO,
                true).subscribe(
                        __ -> log.info("Daily report job completed"),
                        error -> log.error("Daily report job failed", error));
    }

    private void triggerReports() {
        List<ReportPreference> enabledPrefs = reportService.findAllEnabledPreferences();
        log.info("Found {} projects with daily reports enabled", enabledPrefs.size());

        for (var pref : enabledPrefs) {
            try {
                reportService.createAndTriggerReport(pref.workspaceId(), pref.workspaceName(), pref.projectId());
            } catch (Exception e) {
                log.error("Failed to trigger report for project '{}'", pref.projectId(), e);
            }
        }
    }
}
