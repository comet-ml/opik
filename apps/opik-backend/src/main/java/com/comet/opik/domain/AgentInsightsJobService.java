package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJob.EnabledJob;
import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AgentInsightsJobService {

    // Manual-trigger window: the last 24h (no "had-traces" gate — that's a cron-only optimization in
    // OPIK-6853). See design doc §4.
    private static final Duration TRIGGER_WINDOW = Duration.ofHours(24);

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ProjectService projectService;
    private final @NonNull AgentInsightsReportClient agentInsightsReportClient;

    public record CreateResult(AgentInsightsJob job, boolean created) {
    }

    // Creates the job (idempotent on workspace+project). Does NOT trigger a run — use triggerNow for that.
    public Mono<CreateResult> create(@NonNull UUID projectId) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String userName = ctx.getUserName();

        return Mono.fromCallable(() -> {
            // 404 if the project does not exist in this workspace.
            if (projectService.findByIds(workspaceId, Set.of(projectId)).isEmpty()) {
                throw new NotFoundException("Project not found: " + projectId);
            }

            return transactionTemplate.inTransaction(WRITE, handle -> {
                var dao = handle.attach(AgentInsightsJobDAO.class);
                boolean existed = dao.findByProject(workspaceId, projectId).isPresent();
                dao.enable(idGenerator.generateId(), workspaceId, projectId, userName);
                AgentInsightsJob job = dao.findByProject(workspaceId, projectId).orElseThrow();
                return new CreateResult(job, !existed);
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

    // Partial update (today: status; never deletes). 404 if the job does not exist; returns the updated job.
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

    // Triggers an immediate report run for an existing job over the last 24h. 404 if the job does not exist.
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

    // Cross-workspace; used by the scheduler (OPIK-6853), never from a request thread.
    public List<EnabledJob> findAllEnabled() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class).findAllEnabled());
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
            transactionTemplate.inTransaction(WRITE, handle -> {
                handle.attach(AgentInsightsJobDAO.class).markTriggered(job.id(), periodEnd);
                return null;
            });
        } catch (Exception e) {
            // Best-effort: a trigger failure must not fail the request. Catch broadly on purpose — the
            // nightly cron (OPIK-6853) retries, so any failure here is non-fatal.
            log.error("Failed to trigger Agent Insights run for project '{}'", job.projectId(), e);
        }
    }
}
