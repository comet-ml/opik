package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.ReportPreference;
import com.comet.opik.domain.ReportService;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.On;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Slf4j
@Singleton
@DisallowConcurrentExecution
@On(value = "0 0/10 * * * ?", timeZone = "UTC")
public class OllieDailyReportJob extends Job {

    private static final int WINDOW_MINUTES = 10;
    private static final Lock JOB_LOCK = new Lock("daily_report_job:lock");

    private static final AttributeKey<String> WORKSPACE_ID_KEY = AttributeKey.stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME_KEY = AttributeKey.stringKey("workspace_name");
    private static final AttributeKey<String> ERROR_TYPE_KEY = AttributeKey.stringKey("error_type");

    private final ReportService reportService;
    private final LockService lockService;

    private final LongCounter triggerErrorCounter;

    @Inject
    public OllieDailyReportJob(
            @NonNull ReportService reportService,
            @NonNull LockService lockService) {
        this.reportService = reportService;
        this.lockService = lockService;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.daily_report");

        this.triggerErrorCounter = meter
                .counterBuilder("opik.daily_report.trigger_error")
                .setDescription("Number of report trigger failures")
                .build();
    }

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
                triggerErrorCounter.add(1, Attributes.of(
                        WORKSPACE_ID_KEY, pref.workspaceId(),
                        WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(pref.workspaceName(), pref.workspaceId()),
                        ERROR_TYPE_KEY, e.getClass().getSimpleName()));
            }
        }
    }
}
