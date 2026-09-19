package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsEnrollment;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AgentInsightsJobService {

    private static final Duration TRIGGER_WINDOW = Duration.ofDays(7);

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
    // the report call. The consumer group caps concurrent trigger calls, not concurrent runs — the trigger is
    // acked once the platform accepts it, and the analysis runs detached on the Ollie pod.
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
                            markRunFailed(workspaceId, projectId,
                                    AgentInsightsJob.FailureReason.DID_NOT_START,
                                    "Failed to enqueue diagnostics run");
                        });
    }

    // System context (no request thread): gives an enrolled project that has never had a diagnostic its first
    // one, by claiming its free run on the job row and enqueueing a single run.
    public void autoFirstRun(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull Instant periodStart,
            @NonNull Instant periodEnd) {
        int stamped = transactionTemplate.inTransaction(WRITE,
                handle -> handle.attach(AgentInsightsJobDAO.class).markAutoFirstRun(workspaceId, projectId,
                        RequestContext.SYSTEM_USER));
        if (stamped == 0) {
            log.info("Skipping auto first Agent Insights run for project '{}': no longer awaiting one", projectId);
            return;
        }

        reportPublisher.enqueue(projectId, workspaceId, periodStart, periodEnd, AgentInsightsMetrics.AUTO_FIRST_RUN)
                .subscribe(
                        reportId -> {
                            AgentInsightsMetrics.REPORTS_ENQUEUED.add(1,
                                    AgentInsightsMetrics.ENQUEUE_AUTO_FIRST_RUN_SUCCESS);
                            log.info("Enqueued auto first Agent Insights run reportId='{}' for project '{}'",
                                    reportId, projectId);
                        },
                        error -> {
                            AgentInsightsMetrics.REPORTS_ENQUEUED.add(1,
                                    AgentInsightsMetrics.ENQUEUE_AUTO_FIRST_RUN_FAILURE);
                            log.error("Failed to enqueue auto first Agent Insights run for project '{}'", projectId,
                                    error);
                            markRunFailed(workspaceId, projectId, AgentInsightsJob.FailureReason.DID_NOT_START,
                                    "Failed to enqueue diagnostics run");
                        });
    }

    // System context (no request thread): records a run failure with an explicit workspace id. Best-effort.
    public void markRunFailed(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull String code,
            String detail) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(ReportFailureDAO.class).insert(idGenerator.generateId(), workspaceId,
                    ReportFailureDAO.AGENT_INSIGHTS_TYPE, projectId, code, detail, RequestContext.SYSTEM_USER);
            if (AgentInsightsJob.FailureReason.OUT_OF_CREDITS.equals(code)
                    && handle.attach(AgentInsightsJobDAO.class)
                            .disableIfEnabled(workspaceId, projectId, RequestContext.SYSTEM_USER) > 0) {
                log.info("Disabled the Agent Insights schedule for project '{}' in workspace '{}': out of credits",
                        projectId, workspaceId);
            }
            return null;
        });
    }

    // Internal, cross-workspace: enrols the given projects in the rollout, or clears them. Idempotent, so
    // re-sending the same list is a no-op. Projects whose automatic run already happened are reported rather
    // than enrolled, since enrolling them again would have no effect.
    public AgentInsightsEnrollment.Response enrolInAutoFirstRun(boolean enrol, @NonNull List<UUID> projectIds) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AgentInsightsJobDAO.class);

            if (!enrol) {
                int cleared = dao.clearEnrolment(projectIds, RequestContext.SYSTEM_USER);
                log.info("Cleared Agent Insights enrolment for {} of {} projects", cleared, projectIds.size());
                return AgentInsightsEnrollment.Response.builder()
                        .cleared(cleared)
                        .unknownProjectIds(Set.of())
                        .alreadyRunProjectIds(Set.of())
                        .build();
            }

            Set<UUID> existing = dao.findExistingProjectIds(projectIds);
            Set<UUID> alreadyRun = dao.findProjectIdsAlreadyAutoRun(projectIds);
            Set<UUID> unknown = projectIds.stream().filter(id -> !existing.contains(id))
                    .collect(Collectors.toSet());

            int enrolled = 0;
            for (UUID projectId : projectIds) {
                if (!existing.contains(projectId) || alreadyRun.contains(projectId)) {
                    continue;
                }
                enrolled += dao.enrolInAutoFirstRun(idGenerator.generateId(), projectId,
                        RequestContext.SYSTEM_USER) > 0
                                ? 1
                                : 0;
            }

            log.info("Enrolled {} of {} projects in the Agent Insights auto-first-run rollout "
                    + "(unknown: {}, already run: {})", enrolled, projectIds.size(), unknown, alreadyRun);

            return AgentInsightsEnrollment.Response.builder()
                    .enrolled(enrolled)
                    .unknownProjectIds(unknown)
                    .alreadyRunProjectIds(alreadyRun)
                    .build();
        });
    }

    // Called when the free-run budget is spent, with the project whose run was rejected. Projects still owed
    // a run fall back to the standard empty state on their next page load. Projects that already ran keep
    // auto_first_run_at, which is what marks their first run as automatic.
    public int cancelAutoFirstRunRollout(@NonNull String workspaceId, @NonNull UUID projectId) {
        // Separate transactions, unwind first. A rejected batch comes back concurrently, and taking a project's own
        // row before scanning for the rest of the rollout inverts lock order between them: MySQL resolves the
        // deadlock by rolling one back, unwind included, and that project then reads as already run forever.
        // Committed on its own, the unwind cannot be lost; the cancel is idempotent, so any one of the batch's
        // rejections completing it is enough.
        transactionTemplate.inTransaction(WRITE, handle -> handle.attach(AgentInsightsJobDAO.class)
                .unwindAutoFirstRun(workspaceId, projectId, RequestContext.SYSTEM_USER));
        return transactionTemplate.inTransaction(WRITE, handle -> handle.attach(AgentInsightsJobDAO.class)
                .cancelAutoFirstRunRollout(RequestContext.SYSTEM_USER));
    }

    // Cross-workspace; the auto-first-run sweep's candidate set — projects enrolled in the rollout whose
    // run has not been enqueued yet.
    public List<EnabledJob> findAwaitingFirstRun() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class).findAwaitingFirstRun());
    }

    // Cross-workspace; used by the daily sweep (OPIK-6853), never from a request thread. The DAO's JOIN
    // with projects already filters out jobs whose project was deleted.
    public List<EnabledJob> findAllEnabled() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class).findAllEnabled());
    }
}
