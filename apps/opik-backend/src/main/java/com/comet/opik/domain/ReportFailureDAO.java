package com.comet.opik.domain;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.UUID;

/**
 * Generic, append-only failure log for reports/jobs across features, keyed by ({@code type}, {@code entity_id}).
 * Each feature inserts with its own {@code type} discriminator; e.g. Agent Insights uses
 * {@link #AGENT_INSIGHTS_TYPE} with the project id as the entity. Readers look up the latest row per entity.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface ReportFailureDAO {

    String AGENT_INSIGHTS_TYPE = "agent_insights";

    @SqlUpdate("""
            INSERT INTO report_failures
                (id, workspace_id, type, entity_id, reason, detail, created_by, last_updated_by)
            VALUES (:id, :workspaceId, :type, :entityId, :reason, :detail, :userName, :userName)
            """)
    void insert(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("type") String type,
            @Bind("entityId") UUID entityId,
            @Bind("reason") String reason,
            @Bind("detail") String detail,
            @Bind("userName") String userName);
}
