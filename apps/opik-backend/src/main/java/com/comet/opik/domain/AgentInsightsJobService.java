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

    // On enable we trigger an immediate run over the last 24h (no "had-traces" gate — that's a cron-only
    // optimization in OPIK-6853). See design doc §4 / open-question #2.
    private static final Duration ENABLE_TRIGGER_WINDOW = Duration.ofHours(24);

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ProjectService projectService;
    private final @NonNull AgentInsightsReportClient agentInsightsReportClient;

    public record EnableResult(AgentInsightsJob job, boolean created) {
    }

    public Mono<EnableResult> enable(@NonNull UUID projectId) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String workspaceName = ctx.getWorkspaceName();
        String userName = ctx.getUserName();

        return Mono.fromCallable(() -> {
            // 404 if the project does not exist in this workspace (also yields the name for the trigger payload).
            String projectName = projectService.findByIds(workspaceId, Set.of(projectId))
                    .stream().findFirst().map(Project::name)
                    .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

            EnableResult result = transactionTemplate.inTransaction(WRITE, handle -> {
                var dao = handle.attach(AgentInsightsJobDAO.class);
                boolean existed = dao.findByProject(workspaceId, projectId).isPresent();
                dao.enable(idGenerator.generateId(), workspaceId, projectId, userName);
                AgentInsightsJob job = dao.findByProject(workspaceId, projectId).orElseThrow();
                return new EnableResult(job, !existed);
            });

            // Immediate first run — best-effort, fire-and-forget; never fails the enable request.
            triggerImmediate(result.job(), workspaceName, projectName);

            return result;
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

    public Mono<Void> disable(@NonNull UUID projectId) {
        var ctx = requestContext.get();
        String workspaceId = ctx.getWorkspaceId();
        String userName = ctx.getUserName();
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AgentInsightsJobDAO.class);
            // 404 is about existence, not change-count: disabling an already-disabled job is idempotent.
            if (dao.findByProject(workspaceId, projectId).isEmpty()) {
                throw new NotFoundException("Agent insights job not found for project: " + projectId);
            }
            dao.disable(workspaceId, projectId, userName);
            return null;
        })).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // Cross-workspace; used by the scheduler (OPIK-6853), never from a request thread.
    public List<EnabledJob> findAllEnabled() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class).findAllEnabled());
    }

    private void triggerImmediate(AgentInsightsJob job, String workspaceName, String projectName) {
        try {
            if (!agentInsightsReportClient.isEnabled()) {
                log.info("Agent Insights trigger disabled; skipping immediate run for project '{}'",
                        job.projectId());
                return;
            }
            Instant periodEnd = Instant.now();
            Instant periodStart = periodEnd.minus(ENABLE_TRIGGER_WINDOW);
            agentInsightsReportClient.triggerAgentInsights(idGenerator.generateId().toString(),
                    job.projectId(), projectName, workspaceName, periodStart, periodEnd);
            transactionTemplate.inTransaction(WRITE, handle -> {
                handle.attach(AgentInsightsJobDAO.class).markTriggered(job.id(), periodEnd);
                return null;
            });
        } catch (Exception e) {
            // Best-effort: the immediate run must never fail the enable request (design doc §4). Catch
            // broadly on purpose — the nightly cron (OPIK-6853) retries, so any failure here is non-fatal.
            log.error("Failed to trigger immediate Agent Insights run for project '{}'", job.projectId(), e);
        }
    }
}
