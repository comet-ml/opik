package com.comet.opik.domain;

import com.comet.opik.api.OllieReport.OllieReportPage;
import com.comet.opik.api.OllieReport.ReportStatus;
import com.comet.opik.api.ReportPreference;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ReportService {

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull OrchestratorClient orchestratorClient;
    private final @NonNull ProjectService projectService;

    public Mono<UUID> generateReport(@NonNull UUID projectId) {
        var ctx = requestContext.get();
        return Mono.fromCallable(() -> createAndTriggerReport(
                ctx.getWorkspaceId(), ctx.getWorkspaceName(), projectId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public UUID createAndTriggerReport(@NonNull String workspaceId, @NonNull String workspaceName,
            @NonNull UUID projectId) {
        UUID reportId = idGenerator.generateId();

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(OllieReportDAO.class)
                    .insert(reportId, workspaceId, projectId, ReportStatus.PENDING.getValue());
            return null;
        });

        String projectName = projectService.findByIds(workspaceId, Set.of(projectId))
                .stream().findFirst().map(p -> p.name()).orElse(projectId.toString());

        orchestratorClient.triggerReportGeneration(
                reportId.toString(), projectId.toString(), projectName,
                workspaceName,
                () -> markReportFailed(reportId, workspaceId, projectId));

        return reportId;
    }

    public Mono<Void> updateReport(@NonNull UUID projectId, @NonNull UUID reportId,
            @NonNull ReportStatus status, String content, String sessionId,
            JsonNode recommendedActions) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            int updated = handle.attach(OllieReportDAO.class)
                    .update(reportId, workspaceId, projectId, content, sessionId, recommendedActions,
                            status.getValue());
            if (updated == 0) {
                throw new NotFoundException("Report not found or already processed: " + reportId);
            }
            return null;
        })).subscribeOn(Schedulers.boundedElastic()).then();
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
                                .orElse(ReportPreference.defaultForProject(projectId))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ReportPreference> updatePreference(@NonNull UUID projectId, boolean enabled) {
        var ctx = requestContext.get();

        return Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ReportPreferenceDAO.class);
            dao.upsert(ctx.getWorkspaceId(), ctx.getWorkspaceName(), projectId, enabled,
                    ReportPreference.DEFAULT_SCHEDULE_TIME_UTC);
            return dao.findByProjectId(ctx.getWorkspaceId(), projectId).orElseThrow();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public List<ReportPreference> findAllEnabledPreferences() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(ReportPreferenceDAO.class).findAllEnabled());
    }

    private void markReportFailed(UUID reportId, String workspaceId, UUID projectId) {
        try {
            transactionTemplate.inTransaction(WRITE, handle -> {
                handle.attach(OllieReportDAO.class)
                        .update(reportId, workspaceId, projectId, null, null, null, ReportStatus.FAILED.getValue());
                return null;
            });
            log.info("Marked report '{}' as failed", reportId);
        } catch (Exception e) {
            log.error("Failed to mark report '{}' as failed", reportId, e);
        }
    }

    public void failStaleReports() {
        transactionTemplate.inTransaction(WRITE, handle -> {
            int failed = handle.attach(OllieReportDAO.class).failStaleReports();
            if (failed > 0) {
                log.info("Marked {} stale pending reports as failed", failed);
            }
            return null;
        });
    }
}
