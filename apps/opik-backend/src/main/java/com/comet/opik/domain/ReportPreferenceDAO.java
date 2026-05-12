package com.comet.opik.domain;

import com.comet.opik.api.ReportPreference;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterConstructorMapper(ReportPreference.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface ReportPreferenceDAO {

    @SqlUpdate("INSERT INTO report_preferences (workspace_id, workspace_name, project_id, enabled, schedule_time_utc) "
            +
            "VALUES (:workspaceId, :workspaceName, :projectId, :enabled, :scheduleTimeUtc) " +
            "ON DUPLICATE KEY UPDATE enabled = :enabled, workspace_name = :workspaceName")
    void upsert(@Bind("workspaceId") String workspaceId,
            @Bind("workspaceName") String workspaceName,
            @Bind("projectId") UUID projectId,
            @Bind("enabled") boolean enabled,
            @Bind("scheduleTimeUtc") String scheduleTimeUtc);

    @SqlQuery("SELECT * FROM report_preferences WHERE workspace_id = :workspaceId AND project_id = :projectId")
    Optional<ReportPreference> findByProjectId(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId);

    @SqlQuery("SELECT * FROM report_preferences WHERE enabled = true")
    List<ReportPreference> findAllEnabled();
}
