package com.comet.opik.domain;

import com.comet.opik.api.OllieReport;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.JsonNodeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(OllieReport.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
@RegisterColumnMapper(JsonNodeColumnMapper.class)
interface OllieReportDAO {

    @SqlUpdate("""
            INSERT INTO ollie_reports (id, workspace_id, project_id, status)
            SELECT :id, :workspaceId, :projectId, :status
            WHERE NOT EXISTS (SELECT 1 FROM ollie_reports
                WHERE workspace_id = :workspaceId AND project_id = :projectId AND status = 'pending')
            """)
    int insert(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("status") String status);

    @SqlUpdate("""
            UPDATE ollie_reports
            SET content = :content, session_id = :sessionId,
                recommended_actions = :recommendedActions, status = :status
            WHERE id = :id AND workspace_id = :workspaceId
                AND project_id = :projectId AND status = 'pending'
            """)
    int update(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("content") String content,
            @Bind("sessionId") String sessionId,
            @Bind("recommendedActions") JsonNode recommendedActions,
            @Bind("status") String status);

    @SqlQuery("""
            SELECT * FROM ollie_reports
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """)
    List<OllieReport> findByProjectId(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("""
            SELECT COUNT(*) FROM ollie_reports
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    long countByProjectId(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId);

    @SqlUpdate("""
            UPDATE ollie_reports SET status = 'failed'
            WHERE status = 'pending' AND created_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)
            """)
    int failStaleReports();
}
