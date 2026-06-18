package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.Project;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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
    private final @NonNull AgentInsightsReportClient agentInsightsReportClient;

    // Creates the job; 409 if one already exists for the (workspace, project). Does not trigger a run.
    public Mono<AgentInsightsJob> create(@NonNull UUID projectId) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String userName = ctx.getUserName();

        return Mono.fromCallable(() -> {
            if (projectService.findByIds(workspaceId, Set.of(projectId)).isEmpty()) {
                throw new NotFoundException("Project not found: " + projectId);
            }

            return transactionTemplate.inTransaction(WRITE, handle -> {
                var dao = handle.attach(AgentInsightsJobDAO.class);
                if (dao.findByProject(workspaceId, projectId).isPresent()) {
                    throw new EntityAlreadyExistsException(new ErrorMessage(409,
                            "Agent insights job already exists for project: " + projectId));
                }
                dao.create(idGenerator.generateId(), workspaceId, projectId, userName);
                return dao.findByProject(workspaceId, projectId).orElseThrow();
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<AgentInsightsJob> getByProject(@NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class)
                        .findByProject(workspaceId, projectId)
                        .orElseThrow(() -> new NotFoundException(
                                "Agent insights job not found for project: " + projectId))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // Partial update (status); never deletes.
    public Mono<AgentInsightsJob> update(@NonNull UUID projectId, @NonNull AgentInsightsJob.Status status) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String userName = ctx.getUserName();
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AgentInsightsJobDAO.class);
            if (dao.findByProject(workspaceId, projectId).isEmpty()) {
                throw new NotFoundException("Agent insights job not found for project: " + projectId);
            }
            dao.updateStatus(workspaceId, projectId, status.getValue(), userName);
            return dao.findByProject(workspaceId, projectId).orElseThrow();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    // Triggers an immediate run for an existing job (last 24h). 404 if absent.
    public Mono<Void> triggerNow(@NonNull UUID projectId) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String workspaceName = ctx.getWorkspaceName();
        return Mono.fromCallable(() -> {
            String projectName = projectService.findByIds(workspaceId, Set.of(projectId))
                    .stream().findFirst().map(Project::name)
                    .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
            AgentInsightsJob job = transactionTemplate.inTransaction(READ_ONLY,
                    handle -> handle.attach(AgentInsightsJobDAO.class)
                            .findByProject(workspaceId, projectId)
                            .orElseThrow(() -> new NotFoundException(
                                    "Agent insights job not found for project: " + projectId)));
            triggerImmediate(job, workspaceName, projectName);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void triggerImmediate(AgentInsightsJob job, String workspaceName, String projectName) {
        try {
            if (!agentInsightsReportClient.isEnabled()) {
                log.info("Agent Insights trigger disabled; skipping run for project '{}'", job.projectId());
                return;
            }
            Instant periodEnd = Instant.now();
            Instant periodStart = periodEnd.minus(TRIGGER_WINDOW);
            agentInsightsReportClient.triggerAgentInsights(idGenerator.generateId().toString(),
                    job.projectId(), projectName, workspaceName, periodStart, periodEnd);
        } catch (Exception e) {
            // Best-effort: a trigger failure must not fail the request.
            log.error("Failed to trigger Agent Insights run for project '{}'", job.projectId(), e);
        }
    }
}
