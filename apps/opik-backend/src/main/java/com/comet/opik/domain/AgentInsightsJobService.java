package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
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

import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AgentInsightsJobService {

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ProjectService projectService;

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

    // 404 if the job does not exist.
    public void triggerNow(@NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class)
                        .findByProject(workspaceId, projectId)
                        .orElseThrow(() -> new NotFoundException(
                                "Agent insights job not found for project: " + projectId)));
        // TODO(OPIK-6853): enqueue the run on a bounded async queue (Redisson) for the scheduler
        // worker to execute. Until then the trigger is accepted but performs no work.
        log.info("Agent Insights run requested for project '{}' (no-op until OPIK-6853)", projectId);
    }
}
