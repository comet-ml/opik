package com.comet.opik.domain.ailabels;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

/** The ai-labels opt-in. */
public interface AiLabelWorkspaceDAO {

    @SqlQuery("SELECT workspace_id FROM ai_label_workspaces WHERE enabled = TRUE ORDER BY workspace_id")
    List<String> enabledWorkspaceIds();

    @SqlQuery("SELECT enabled FROM ai_label_workspaces WHERE workspace_id = :workspaceId")
    Optional<Boolean> enabled(@Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            INSERT INTO ai_label_workspaces (workspace_id, enabled, added_by)
            VALUES (:workspaceId, :enabled, :addedBy)
            ON DUPLICATE KEY UPDATE enabled = :enabled, added_by = :addedBy
            """)
    void upsert(@Bind("workspaceId") String workspaceId, @Bind("enabled") boolean enabled,
            @Bind("addedBy") String addedBy);
}
