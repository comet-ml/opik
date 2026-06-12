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

    @SqlUpdate("""
            INSERT INTO report_preferences (workspace_id, workspace_name, project_id, enabled, schedule_time, custom_prompt)
            VALUES (:workspaceId, :workspaceName, :projectId, :enabled, COALESCE(:scheduleTime, '05:00:00'), :customPrompt)
            ON DUPLICATE KEY UPDATE enabled = :enabled, workspace_name = :workspaceName,
                schedule_time = COALESCE(:scheduleTime, schedule_time),
                custom_prompt = COALESCE(:customPrompt, custom_prompt)
            """)
    void upsert(@Bind("workspaceId") String workspaceId,
            @Bind("workspaceName") String workspaceName,
            @Bind("projectId") UUID projectId,
            @Bind("enabled") boolean enabled,
            @Bind("scheduleTime") String scheduleTime,
            @Bind("customPrompt") String customPrompt);

    @SqlQuery("""
            SELECT * FROM report_preferences
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    Optional<ReportPreference> findByProjectId(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId);

    @SqlQuery("""
            SELECT * FROM report_preferences
            WHERE enabled = true
                AND schedule_time >= :windowStart AND schedule_time < :windowEnd
            """)
    List<ReportPreference> findAllEnabledInTimeWindow(@Bind("windowStart") String windowStart,
            @Bind("windowEnd") String windowEnd);
}
