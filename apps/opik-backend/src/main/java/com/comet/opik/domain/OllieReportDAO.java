package com.comet.opik.domain;

import com.comet.opik.api.OllieReport;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(OllieReport.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface OllieReportDAO {

    @SqlUpdate("INSERT INTO ollie_reports (id, workspace_id, project_id, status) " +
            "VALUES (:id, :workspaceId, :projectId, :status)")
    void insert(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("status") String status);

    @SqlUpdate("UPDATE ollie_reports SET content = :content, session_id = :sessionId, " +
            "status = :status WHERE id = :id AND workspace_id = :workspaceId")
    int update(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("content") String content,
            @Bind("sessionId") String sessionId,
            @Bind("status") String status);

    @SqlQuery("SELECT * FROM ollie_reports WHERE project_id = :projectId " +
            "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    List<OllieReport> findByProjectId(@Bind("projectId") UUID projectId,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("SELECT COUNT(*) FROM ollie_reports WHERE project_id = :projectId")
    long countByProjectId(@Bind("projectId") UUID projectId);

    @SqlUpdate("UPDATE ollie_reports SET status = 'failed' " +
            "WHERE status = 'pending' AND created_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)")
    int failStaleReports();
}
