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
import java.time.LocalTime;
import java.util.List;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Runs every 10 minutes and triggers report generation for projects
 * whose schedule_time falls within the previous 10-minute window.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@On(value = "0 0/10 * * * ?", timeZone = "UTC")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OllieDailyReportJob extends Job {

    private static final int WINDOW_MINUTES = 10;
    private static final Lock JOB_LOCK = new Lock("daily_report_job:lock");

    private final @NonNull ReportService reportService;
    private final @NonNull LockService lockService;

    record TimeWindow(String start, String end) {
    }

    static TimeWindow computeWindow(LocalTime now) {
        int minute = now.getMinute();
        LocalTime windowEnd = now.withMinute(minute - (minute % WINDOW_MINUTES)).withSecond(0).withNano(0);
        LocalTime windowStart = windowEnd.minusMinutes(WINDOW_MINUTES);

        String startStr = windowStart.toString();
        // At 00:00, window is [23:50, 00:00) — use "24:00:00" so the SQL range is continuous
        String endStr = windowEnd.equals(LocalTime.MIDNIGHT) ? "24:00:00" : windowEnd.toString();
        return new TimeWindow(startStr, endStr);
    }

    @Override
    public void doJob(JobExecutionContext context) {
        TimeWindow window = computeWindow(LocalTime.now(java.time.ZoneOffset.UTC));
        String startStr = window.start();
        String endStr = window.end();

        log.info("Daily report job checking window [{}, {})", startStr, endStr);

        lockService.bestEffortLock(
                JOB_LOCK,
                Mono.fromRunnable(() -> triggerReports(startStr, endStr)),
                Mono.defer(() -> {
                    log.debug("Could not acquire lock for daily report job, another instance is running");
                    return Mono.empty();
                }),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                true).subscribe(
                        __ -> log.info("Daily report job completed"),
                        error -> log.error("Daily report job failed", error));
    }

    private void triggerReports(String windowStart, String windowEnd) {
        List<ReportPreference> prefs = reportService.findEnabledPreferencesInTimeWindow(windowStart, windowEnd);
        log.info("Found {} projects scheduled in window [{}, {})", prefs.size(), windowStart, windowEnd);

        for (var pref : prefs) {
            try {
                reportService.createAndTriggerReport(pref.workspaceId(), pref.workspaceName(), pref.projectId());
            } catch (Exception e) {
                log.error("Failed to trigger report for project '{}'", pref.projectId(), e);
            }
        }
    }
}
