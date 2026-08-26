package com.comet.opik.domain;

import com.comet.opik.api.OllieReport;
import com.comet.opik.api.OllieReport.OllieReportPage;
import com.comet.opik.api.OllieReport.ReportStatus;
import com.comet.opik.api.ReportPreference;
import com.comet.opik.domain.OllieReportDAO.WorkspacePendingCount;
import com.comet.opik.infrastructure.ReportGenerationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Singleton
@Slf4j
public class ReportService {

    private static final AttributeKey<String> RESULT_KEY = stringKey("result");
    private static final AttributeKey<String> STAGE_KEY = stringKey("stage");
    private static final String STAGE_TRIGGER = "trigger";
    private static final String STAGE_GENERATION = "generation";
    private static final String STAGE_SWEEP = "sweep";
    private static final AttributeKey<String> FAILURE_REASON_KEY = stringKey("failure_reason");
    private static final String OTHER_FAILURE_REASON = "other";
    private static final AttributeKey<String> WORKSPACE_ID_KEY = stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME_KEY = stringKey("workspace_name");

    private final TransactionTemplate transactionTemplate;
    private final IdGenerator idGenerator;
    private final Provider<RequestContext> requestContext;
    private final OrchestratorClient orchestratorClient;
    private final ProjectService projectService;
    private final ReportGenerationConfig reportGenerationConfig;

    private final LongCounter triggeredCounter;
    private final AtomicReference<Map<String, Long>> pendingByWorkspace = new AtomicReference<>(Map.of());

    private final LongCounter finishedCounter;
    private final LongHistogram endToEndDuration;

    @Inject
    public ReportService(
            @NonNull TransactionTemplate transactionTemplate,
            @NonNull IdGenerator idGenerator,
            @NonNull Provider<RequestContext> requestContext,
            @NonNull OrchestratorClient orchestratorClient,
            @NonNull ProjectService projectService,
            @NonNull @Config("reportGeneration") ReportGenerationConfig reportGenerationConfig) {
        this.transactionTemplate = transactionTemplate;
        this.idGenerator = idGenerator;
        this.requestContext = requestContext;
        this.orchestratorClient = orchestratorClient;
        this.projectService = projectService;
        this.reportGenerationConfig = reportGenerationConfig;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.daily_report");

        this.triggeredCounter = meter
                .counterBuilder("opik.daily_report.triggered")
                .setDescription("Number of reports triggered for generation (scheduled and manual)")
                .build();

        this.finishedCounter = meter
                .counterBuilder("opik.daily_report.finished")
                .setDescription("Number of reports finalized, by result (completed / failed), the stage a "
                        + "failure occurred in (trigger / generation / sweep), and failure_reason where the stage "
                        + "does not already imply it")
                .build();

        this.endToEndDuration = meter
                .histogramBuilder("opik.daily_report.end_to_end_duration")
                .setDescription("Time from report creation to completion callback")
                .setUnit("ms")
                .ofLongs()
                .build();

        meter.gaugeBuilder("opik.daily_report.pending")
                .setDescription("Reports currently pending, per workspace, as of the last snapshot refresh. Every "
                        + "replica reports the same database-wide count, so deduplicate replicas with "
                        + "max by (workspace_id) before summing across workspaces")
                .ofLongs()
                .buildWithCallback(this::recordPendingReports);
    }

    private void recordPendingReports(ObservableLongMeasurement measurement) {
        pendingByWorkspace.get().forEach((workspaceId, count) -> measurement
                .record(count, Attributes.of(WORKSPACE_ID_KEY, workspaceId)));
    }

    public void refreshPendingReports() {
        try {
            pendingByWorkspace.set(transactionTemplate.inTransaction(READ_ONLY,
                    handle -> handle.attach(OllieReportDAO.class).countPendingByWorkspace())
                    .stream()
                    .collect(Collectors.toMap(WorkspacePendingCount::workspaceId,
                            WorkspacePendingCount::pendingCount)));
        } catch (Exception e) {
            log.warn("Failed to refresh pending report count for metrics; keeping the previous snapshot", e);
        }
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
                reason -> markReportFailed(reportId, workspaceId, workspaceName, projectId, reason));

        triggeredCounter.add(1, Attributes.of(
                WORKSPACE_ID_KEY, workspaceId,
                WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, workspaceId)));

        return reportId;
    }

    public Mono<Void> updateReport(@NonNull UUID projectId, @NonNull UUID reportId,
            @NonNull ReportStatus status, String content, String sessionId,
            JsonNode recommendedActions, String failureReason) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String workspaceName = ctx.getWorkspaceName();
        if (status == ReportStatus.PENDING) {
            // Accepting this would emit a finished datapoint and record a duration for a report that is still
            // pending, then count it again when the sweep terminates it.
            throw new BadRequestException("Report completion requires a terminal status, got: " + status.getValue());
        }
        // A reason only describes a failure; ignore one sent alongside a completed report
        String reason = status == ReportStatus.FAILED ? failureReason : null;
        if (StringUtils.isNotBlank(reason) && !OllieReport.FailureReason.OUT_OF_CREDITS.equals(reason)) {
            // Recorded anyway, but the UI will fall back to a generic failure and the metric buckets it as 'other'
            log.warn("Unrecognised report failure reason '{}' for reportId='{}'; add it to FailureReason and give "
                    + "the frontend a case for it if the generator now emits it", reason, reportId);
        }

        return Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(OllieReportDAO.class);

            int updated = dao.update(reportId, workspaceId, projectId, content, sessionId, recommendedActions,
                    status.getValue(), reason);
            if (updated == 0) {
                throw new NotFoundException("Report not found or already processed: " + reportId);
            }

            return dao.getCreatedAt(reportId, workspaceId);
        }))
                .doOnNext(createdAt -> recordCompletionMetrics(workspaceId, workspaceName, status, reason, createdAt))
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

    private void recordCompletionMetrics(String workspaceId, String workspaceName, ReportStatus status,
            String failureReason, Instant createdAt) {
        boolean completed = status == ReportStatus.COMPLETED;
        Attributes outcome = outcomeAttributes(workspaceId, workspaceName, completed ? "completed" : "failed");

        finishedCounter.add(1, completed
                ? outcome
                : failureAttributes(workspaceId, workspaceName, STAGE_GENERATION, failureReason));
        endToEndDuration.record(Instant.now().toEpochMilli() - createdAt.toEpochMilli(), outcome);
    }

    private static Attributes outcomeAttributes(String workspaceId, String workspaceName, String result) {
        return Attributes.of(
                RESULT_KEY, result,
                WORKSPACE_ID_KEY, workspaceId,
                WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, workspaceId));
    }

    private static Attributes failureAttributes(String workspaceId, String workspaceName, String stage,
            String failureReason) {
        var builder = outcomeAttributes(workspaceId, workspaceName, "failed").toBuilder().put(STAGE_KEY, stage);
        if (StringUtils.isNotBlank(failureReason)) {
            builder.put(FAILURE_REASON_KEY, OllieReport.FailureReason.OUT_OF_CREDITS.equals(failureReason)
                    ? failureReason
                    : OTHER_FAILURE_REASON);
        }
        return builder.build();
    }

    private void markReportFailed(UUID reportId, String workspaceId, String workspaceName, UUID projectId,
            String failureReason) {
        try {
            int updated = transactionTemplate.inTransaction(WRITE, handle -> handle.attach(OllieReportDAO.class)
                    .update(reportId, workspaceId, projectId, null, null, null, ReportStatus.FAILED.getValue(),
                            failureReason));
            if (updated > 0) {
                String metricReason = OllieReport.FailureReason.TRIGGER_FAILED.equals(failureReason)
                        ? null
                        : failureReason;
                finishedCounter.add(1,
                        failureAttributes(workspaceId, workspaceName, STAGE_TRIGGER, metricReason));
                log.info("Marked report as failed reportId='{}' workspaceId='{}' projectId='{}' reason='{}'",
                        reportId, workspaceId, projectId, failureReason);
            }
        } catch (Exception e) {
            log.error("Failed to mark report as failed reportId='{}' workspaceId='{}' projectId='{}'",
                    reportId, workspaceId, projectId, e);
        }
    }

    public Map<String, Long> failStaleReports() {
        Map<String, Long> sweptByWorkspace = transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(OllieReportDAO.class);
            Map<String, Long> swept = dao
                    .findStalePendingWorkspaceIds(reportGenerationConfig.getStaleReportTimeoutMinutes()).stream()
                    .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
            dao.failStaleReports(reportGenerationConfig.getStaleReportTimeoutMinutes(),
                    OllieReport.FailureReason.STALE_TIMEOUT);
            return swept;
        });

        // Reporting stays outside the transaction: a failure here must not roll back a sweep that already
        // committed, leaving rows pending after the metric has counted them as swept.
        if (!sweptByWorkspace.isEmpty()) {
            log.info("Marked {} stale pending reports as failed",
                    sweptByWorkspace.values().stream().mapToLong(Long::longValue).sum());
            // No reason on the metric: a sweep can only ever be a stale timeout, so stage=sweep already says it.
            // The row still records it, because ollie_reports has no stage column to carry that.
            // workspace_name falls back to the ID: the sweep runs outside a request context and ollie_reports
            // stores only IDs. Nothing queries sweeps by workspace name, so resolving it is not worth a lookup.
            sweptByWorkspace.forEach((workspaceId, count) -> finishedCounter.add(count,
                    failureAttributes(workspaceId, workspaceId, STAGE_SWEEP, null)));
        }
        return sweptByWorkspace;
    }
}
