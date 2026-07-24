package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJob.EnabledJob;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AgentInsightsJobService {

    private static final Duration TRIGGER_WINDOW = Duration.ofHours(24);

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ProjectService projectService;
    private final @NonNull AgentInsightsReportPublisher reportPublisher;

    // Creates the job; 409 if one already exists for the (workspace, project).
    public AgentInsightsJob create(@NonNull UUID projectId) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String userName = ctx.getUserName();

        projectService.validateProjectIdExists(projectId, workspaceId);

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AgentInsightsJobDAO.class);
            // Insert-only; the unique key (workspace_id, project_id) makes this race-safe — a
            // concurrent create surfaces as a constraint violation, mapped to 409.
            return EntityConstraintHandler.handle(() -> {
                dao.create(idGenerator.generateId(), workspaceId, projectId, userName);
                return dao.findByProject(workspaceId, projectId).orElseThrow();
            }).withError(() -> new EntityAlreadyExistsException(new ErrorMessage(409,
                    "Agent insights job already exists for project: " + projectId)));
        });
    }

    public AgentInsightsJob getByProject(@NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class)
                        .findByProject(workspaceId, projectId)
                        .orElseThrow(() -> new NotFoundException(
                                "Agent insights job not found for project: " + projectId)));
    }

    // Partial update (status); never deletes.
    public AgentInsightsJob update(@NonNull UUID projectId, @NonNull AgentInsightsJob.Status status) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String userName = ctx.getUserName();
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AgentInsightsJobDAO.class);
            if (dao.findByProject(workspaceId, projectId).isEmpty()) {
                throw new NotFoundException("Agent insights job not found for project: " + projectId);
            }
            dao.updateStatus(workspaceId, projectId, status.getValue(), userName);
            return dao.findByProject(workspaceId, projectId).orElseThrow();
        });
    }

    // Manual trigger: validate the project (404) and job (404) on the request thread, then enqueue the
    // report run on the bounded Redis-backed queue and return 202 — the request thread never blocks on
    // the report call, and the consumer group caps concurrent runs.
    public void triggerNow(@NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        // Guard against orphaned jobs: a project may have been deleted out from under the job row.
        projectService.validateProjectIdExists(projectId, workspaceId);
        AgentInsightsJob job = transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class)
                        .findByProject(workspaceId, projectId)
                        .orElseThrow(() -> new NotFoundException(
                                "Agent insights job not found for project: " + projectId)));

        Instant periodEnd = Instant.now();
        reportPublisher.enqueue(job.projectId(), workspaceId, periodEnd.minus(TRIGGER_WINDOW), periodEnd,
                AgentInsightsMetrics.MANUAL)
                .subscribe(
                        reportId -> {
                            AgentInsightsMetrics.REPORTS_ENQUEUED.add(1, AgentInsightsMetrics.ENQUEUE_MANUAL_SUCCESS);
                            log.info("Enqueued Agent Insights run reportId='{}' for project '{}'",
                                    reportId, projectId);
                        },
                        error -> {
                            AgentInsightsMetrics.REPORTS_ENQUEUED.add(1, AgentInsightsMetrics.ENQUEUE_MANUAL_FAILURE);
                            log.error("Failed to enqueue Agent Insights run for project '{}'", projectId,
                                    error);
                            // Publisher-side failure: the run never reaches Ollie (which would otherwise report
                            // its own failure), so record it here too, or the UI spins until the client timeout.
                            markRunFailed(workspaceId, projectId, "did_not_start",
                                    "Failed to enqueue diagnostics run");
                        });
    }

    // System context (no request thread): records a run failure with an explicit workspace id. Best-effort.
    public void markRunFailed(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull String code,
            String detail) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(ReportFailureDAO.class).insert(idGenerator.generateId(), workspaceId,
                    ReportFailureDAO.AGENT_INSIGHTS_TYPE, projectId, code, detail, "system");
            return null;
        });
    }

    // Cross-workspace; used by the daily sweep (OPIK-6853), never from a request thread. The DAO's JOIN
    // with projects already filters out jobs whose project was deleted.
    public List<EnabledJob> findAllEnabled() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class).findAllEnabled());
    }
}
