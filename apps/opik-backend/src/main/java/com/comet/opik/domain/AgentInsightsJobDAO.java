package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJob.EnabledJob;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterConstructorMapper(AgentInsightsJob.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AgentInsightsJobDAO {

    @SqlUpdate("""
            INSERT INTO agent_insights_jobs (id, workspace_id, project_id, status, created_by, last_updated_by)
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

    @SqlUpdate("""
            UPDATE agent_insights_jobs SET last_triggered_at = :triggeredAt
            WHERE id = :id
            """)
    void markTriggered(@Bind("id") UUID id, @Bind("triggeredAt") Instant triggeredAt);

    // Cross-workspace — used only by the scheduler (system context), never from a request thread.
    @SqlQuery("""
            SELECT id, workspace_id, project_id FROM agent_insights_jobs
            WHERE status = 'enabled'
            """)
    @RegisterConstructorMapper(EnabledJob.class)
    List<EnabledJob> findAllEnabled();
}
