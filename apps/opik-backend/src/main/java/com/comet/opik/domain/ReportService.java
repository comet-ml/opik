package com.comet.opik.domain;

import com.comet.opik.api.OllieReport.OllieReportPage;
import com.comet.opik.api.OllieReport.ReportStatus;
import com.comet.opik.api.ReportPreference;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Singleton
@Slf4j
public class ReportService {

    private static final int STALE_THRESHOLD_MINUTES = 30;

    private static final AttributeKey<String> RESULT_KEY = stringKey("result");
    private static final AttributeKey<String> WORKSPACE_ID_KEY = stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME_KEY = stringKey("workspace_name");

    private final TransactionTemplate transactionTemplate;
    private final IdGenerator idGenerator;
    private final Provider<RequestContext> requestContext;
    private final OrchestratorClient orchestratorClient;
    private final ProjectService projectService;

    private final LongCounter triggeredCounter;
    private final LongCounter finishedCounter;
    private final LongHistogram endToEndDuration;
    private final LongHistogram scheduledToCompletionDuration;

    @Inject
    public ReportService(
            @NonNull TransactionTemplate transactionTemplate,
            @NonNull IdGenerator idGenerator,
            @NonNull Provider<RequestContext> requestContext,
            @NonNull OrchestratorClient orchestratorClient,
            @NonNull ProjectService projectService) {
        this.transactionTemplate = transactionTemplate;
        this.idGenerator = idGenerator;
        this.requestContext = requestContext;
        this.orchestratorClient = orchestratorClient;
        this.projectService = projectService;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.daily_report");

        this.triggeredCounter = meter
                .counterBuilder("opik.daily_report.triggered")
                .setDescription("Number of reports triggered for generation (scheduled and manual)")
                .build();

        this.finishedCounter = meter
                .counterBuilder("opik.daily_report.finished")
                .setDescription("Number of reports finalized via the completion callback or trigger failure, "
                        + "by result (completed / failed / trigger_failed); stale sweeps are counted separately "
                        + "by opik.daily_report.stale_swept")
                .build();

        this.endToEndDuration = meter
                .histogramBuilder("opik.daily_report.end_to_end_duration")
                .setDescription("Time from report creation to completion callback")
                .setUnit("ms")
                .ofLongs()
                .build();

        this.scheduledToCompletionDuration = meter
                .histogramBuilder("opik.daily_report.scheduled_to_completion_duration")
                .setDescription("Time from user-configured schedule time to completion callback")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    public Mono<UUID> generateReport(@NonNull UUID projectId) {
        var ctx = requestContext.get();
        return Mono.fromCallable(() -> createAndTriggerReport(
                ctx.getWorkspaceId(), ctx.getWorkspaceName(), projectId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public UUID createAndTriggerReport(@NonNull String workspaceId, @NonNull String workspaceName,
            @NonNull UUID projectId) {
        if (!orchestratorClient.isEnabled()) {
            log.warn("Report generation not configured, skipping for project '{}'", projectId);
            return null;
        }

        String projectName = projectService.findByIds(workspaceId, Set.of(projectId))
                .stream().findFirst().map(p -> p.name())
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        UUID reportId = idGenerator.generateId();

        int inserted = transactionTemplate.inTransaction(WRITE, handle -> handle.attach(OllieReportDAO.class)
                .insert(reportId, workspaceId, projectId, ReportStatus.PENDING.getValue()));
        if (inserted == 0) {
            log.info("Report already pending for project '{}', skipping", projectId);
            return null;
        }

        String customPrompt = transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(ReportPreferenceDAO.class)
                        .findByProjectId(workspaceId, projectId)
                        .map(ReportPreference::customPrompt)
                        .orElse(null));

        orchestratorClient.triggerReportGeneration(
                reportId.toString(), projectId.toString(), projectName,
                workspaceName, customPrompt,
                () -> markReportFailed(reportId, workspaceId, workspaceName, projectId));

        triggeredCounter.add(1, Attributes.of(
                WORKSPACE_ID_KEY, workspaceId,
                WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, workspaceId)));

        return reportId;
    }

    public Mono<Void> updateReport(@NonNull UUID projectId, @NonNull UUID reportId,
            @NonNull ReportStatus status, String content, String sessionId,
            JsonNode recommendedActions) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String workspaceName = ctx.getWorkspaceName();

        return Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(OllieReportDAO.class);

            int updated = dao.update(reportId, workspaceId, projectId, content, sessionId, recommendedActions,
                    status.getValue());
            if (updated == 0) {
                throw new NotFoundException("Report not found or already processed: " + reportId);
            }

            return dao.getCreatedAt(reportId, workspaceId);
        }))
                .doOnNext(createdAt -> recordCompletionMetrics(workspaceId, workspaceName, projectId, status,
                        createdAt))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<OllieReportPage> getReports(@NonNull UUID projectId, int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(OllieReportDAO.class);
            int offset = (page - 1) * size;
            var reports = dao.findByProjectId(workspaceId, projectId, size, offset);
            long total = dao.countByProjectId(workspaceId, projectId);
            return OllieReportPage.builder()
                    .page(page)
                    .size(size)
                    .total(total)
                    .content(reports)
                    .build();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ReportPreference> getPreference(@NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return Mono
                .fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                        handle -> handle.attach(ReportPreferenceDAO.class)
                                .findByProjectId(workspaceId, projectId)
                                .orElse(null)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ReportPreference> updatePreference(@NonNull UUID projectId, @NonNull ReportPreference preference) {
        var ctx = requestContext.get();

        return Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ReportPreferenceDAO.class);
            dao.upsert(ctx.getWorkspaceId(), ctx.getWorkspaceName(), projectId, preference.enabled(),
                    preference.scheduleTime(), preference.customPrompt());
            return dao.findByProjectId(ctx.getWorkspaceId(), projectId).orElseThrow();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public List<ReportPreference> findEnabledPreferencesInTimeWindow(String windowStart, String windowEnd) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(ReportPreferenceDAO.class)
                        .findAllEnabledInTimeWindow(windowStart, windowEnd));
    }

    private void recordCompletionMetrics(String workspaceId, String workspaceName, UUID projectId,
            ReportStatus status, Instant createdAt) {
        try {
            Instant now = Instant.now();
            String result = status == ReportStatus.COMPLETED ? "completed" : "failed";
            Attributes attrs = Attributes.of(
                    RESULT_KEY, result,
                    WORKSPACE_ID_KEY, workspaceId,
                    WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, workspaceId));

            finishedCounter.add(1, attrs);
            endToEndDuration.record(now.toEpochMilli() - createdAt.toEpochMilli(), attrs);

            transactionTemplate.inTransaction(READ_ONLY, handle -> handle.attach(ReportPreferenceDAO.class)
                    .findByProjectId(workspaceId, projectId))
                    .map(ReportPreference::scheduleTime)
                    .ifPresent(scheduleTimeStr -> {
                        LocalDate reportDate = createdAt.atZone(ZoneOffset.UTC).toLocalDate();
                        Instant scheduledAt = reportDate.atTime(LocalTime.parse(scheduleTimeStr))
                                .toInstant(ZoneOffset.UTC);
                        if (scheduledAt.isAfter(createdAt)) {
                            scheduledAt = scheduledAt.minusSeconds(86400);
                        }
                        scheduledToCompletionDuration.record(now.toEpochMilli() - scheduledAt.toEpochMilli(), attrs);
                    });
        } catch (Exception e) {
            log.warn("Failed to record completion metrics for project '{}'", projectId, e);
        }
    }

    private void markReportFailed(UUID reportId, String workspaceId, String workspaceName, UUID projectId) {
        try {
            int updated = transactionTemplate.inTransaction(WRITE, handle -> handle.attach(OllieReportDAO.class)
                    .update(reportId, workspaceId, projectId, null, null, null, ReportStatus.FAILED.getValue()));
            if (updated > 0) {
                finishedCounter.add(1, Attributes.of(
                        RESULT_KEY, "trigger_failed",
                        WORKSPACE_ID_KEY, workspaceId,
                        WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, workspaceId)));
                log.info("Marked report as failed reportId='{}' workspaceId='{}' projectId='{}'",
                        reportId, workspaceId, projectId);
            }
        } catch (Exception e) {
            log.error("Failed to mark report as failed reportId='{}' workspaceId='{}' projectId='{}'",
                    reportId, workspaceId, projectId, e);
        }
    }

    public Map<String, Long> failStaleReports() {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(OllieReportDAO.class);
            Map<String, Long> sweptByWorkspace = dao.findStalePendingWorkspaceIds(STALE_THRESHOLD_MINUTES).stream()
                    .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
            int failed = dao.failStaleReports(STALE_THRESHOLD_MINUTES);
            if (failed > 0) {
                log.info("Marked {} stale pending reports as failed", failed);
            }
            return sweptByWorkspace;
        });
    }
}
