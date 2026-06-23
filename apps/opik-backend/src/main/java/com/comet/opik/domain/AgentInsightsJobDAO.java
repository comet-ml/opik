package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJob.EnabledJob;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterConstructorMapper(AgentInsightsJob.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AgentInsightsJobDAO {

    @SqlUpdate("""
            INSERT INTO agent_insights_jobs
                (id, workspace_id, project_id, status, created_by, last_updated_by)
            VALUES (:id, :workspaceId, :projectId, 'enabled', :userName, :userName)
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

    @SqlQuery("""
            SELECT * FROM agent_insights_jobs
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    Optional<AgentInsightsJob> findByProject(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId);

    // Stamp the time a diagnostic report was last generated. Called on every
    // report (including "all clear"); a no-op if the project has no job row.
    // last_updated_by is set alongside so the audit actor stays in sync with the
    // last_updated_at bumped by ON UPDATE.
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
}
