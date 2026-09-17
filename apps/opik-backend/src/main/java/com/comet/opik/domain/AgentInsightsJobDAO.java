package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJob.EnabledJob;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterConstructorMapper(AgentInsightsJob.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AgentInsightsJobDAO {

    @SqlUpdate("""
            INSERT INTO agent_insights_jobs
                (id, workspace_id, project_id, status, created_by, last_updated_by)
            VALUES (:id, :workspaceId, :projectId, 'disabled', :userName, :userName)
            """)
    void create(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE agent_insights_jobs SET status = :status, last_updated_by = :userName
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    int updateStatus(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("status") String status,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE agent_insights_jobs SET status = 'disabled', last_updated_by = :userName
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND status = 'enabled'
            """)
    int disableIfEnabled(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("userName") String userName);

    // The job's "current failure" is the latest report_failures row for this project that landed after the
    // last successful scan (a success advances last_scan_at and supersedes older failures). Surfaced via the
    // last_failure_* aliases so the DTO/JSON shape is unchanged even though failures now live in their own table.
    @SqlQuery("""
            SELECT j.id, j.workspace_id, j.project_id, j.status, j.auto_first_run_enrolled, j.auto_first_run_at,
                   j.last_scan_at,
                   f.reason AS last_failure_reason, f.detail AS last_failure_detail, f.created_at AS last_failed_at,
                   j.created_at, j.created_by, j.last_updated_at, j.last_updated_by
            FROM agent_insights_jobs j
            LEFT JOIN (
                SELECT workspace_id, project_id, reason, detail, created_at,
                       ROW_NUMBER() OVER (PARTITION BY workspace_id, project_id ORDER BY created_at DESC, id DESC) AS rn
                FROM report_failures
                WHERE type = 'agent_insights'
            ) f ON f.workspace_id = j.workspace_id AND f.project_id = j.project_id AND f.rn = 1
                AND f.created_at > COALESCE(j.last_scan_at, '1970-01-01 00:00:00')
            WHERE j.workspace_id = :workspaceId AND j.project_id = :projectId
            """)
    Optional<AgentInsightsJob> findByProject(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId);

    // Stamped when the automatic run is enqueued, so the sweep stops picking the project up and the page
    // can tell that run from a finished one. Manual runs do not set it.
    // Re-checks the enrolment the candidate was selected by, since the rollout can be cancelled mid-sweep:
    // returns 0 for a project unenrolled since, and the caller then skips the trigger.
    @SqlUpdate("""
            UPDATE agent_insights_jobs
            SET auto_first_run_at = CURRENT_TIMESTAMP(6), last_updated_by = :userName
            WHERE workspace_id = :workspaceId AND project_id = :projectId
                AND auto_first_run_enrolled AND auto_first_run_at IS NULL
            """)
    int markAutoFirstRun(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE agent_insights_jobs
            SET last_scan_at = CURRENT_TIMESTAMP(6), last_updated_by = :userName
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    int markScanned(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("userName") String userName);

    // Cross-workspace — used only by the daily sweep (system context), never from a request thread.
    // INNER JOIN projects so jobs whose project was deleted are filtered out at the source (no per-job
    // existence check in the sweep loop).
    @SqlQuery("""
            SELECT j.id, j.workspace_id, j.project_id
            FROM agent_insights_jobs j
            INNER JOIN projects p ON p.id = j.project_id AND p.workspace_id = j.workspace_id
            WHERE j.status = 'enabled'
            """)
    @RegisterConstructorMapper(EnabledJob.class)
    List<EnabledJob> findAllEnabled();

    // Cross-workspace — used only by the auto-first-run sweep (system context). Projects enrolled in the
    // rollout that have not had a run enqueued yet. INNER JOIN projects so a deleted project drops out, as
    // in findAllEnabled.
    @SqlQuery("""
            SELECT j.id, j.workspace_id, j.project_id
            FROM agent_insights_jobs j
            INNER JOIN projects p ON p.id = j.project_id AND p.workspace_id = j.workspace_id
            WHERE j.auto_first_run_enrolled AND j.auto_first_run_at IS NULL
            """)
    @RegisterConstructorMapper(EnabledJob.class)
    List<EnabledJob> findAwaitingFirstRun();

    @SqlQuery("SELECT id FROM projects WHERE id IN (<projectIds>)")
    Set<UUID> findExistingProjectIds(@BindList("projectIds") Collection<UUID> projectIds);

    @SqlQuery("""
            SELECT project_id FROM agent_insights_jobs
            WHERE project_id IN (<projectIds>) AND auto_first_run_at IS NOT NULL
            """)
    Set<UUID> findProjectIdsAlreadyAutoRun(@BindList("projectIds") Collection<UUID> projectIds);

    // Enrols one project: creates the job row if absent, otherwise just flags it. The workspace comes from
    // the project row, so callers only need the project id, and the INSERT no-ops when the project is gone.
    @SqlUpdate("""
            INSERT INTO agent_insights_jobs
                (id, workspace_id, project_id, status, auto_first_run_enrolled, created_by, last_updated_by)
            SELECT :id, p.workspace_id, p.id, 'disabled', TRUE, :userName, :userName
            FROM projects p WHERE p.id = :projectId
            ON DUPLICATE KEY UPDATE auto_first_run_enrolled = TRUE, last_updated_by = :userName
            """)
    int enrolInAutoFirstRun(@Bind("id") UUID id,
            @Bind("projectId") UUID projectId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE agent_insights_jobs SET auto_first_run_enrolled = FALSE, last_updated_by = :userName
            WHERE project_id IN (<projectIds>) AND auto_first_run_enrolled
            """)
    int clearEnrolment(@BindList("projectIds") Collection<UUID> projectIds, @Bind("userName") String userName);

    // A run rejected before it started: clears its enqueue stamp, which would otherwise read as a run in flight
    // and then, once the rollout is cancelled around it, as a run that already happened.
    @SqlUpdate("""
            UPDATE agent_insights_jobs
            SET auto_first_run_enrolled = FALSE, auto_first_run_at = NULL, last_updated_by = :userName
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    int unwindAutoFirstRun(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE agent_insights_jobs SET auto_first_run_enrolled = FALSE, last_updated_by = :userName
            WHERE auto_first_run_enrolled
            """)
    int cancelAutoFirstRunRollout(@Bind("userName") String userName);
}
