package com.comet.opik.domain.topics;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

/**
 * The topics opt-in. Topics read the ai-labels trace_summary, so a workspace runs topics only when it is enabled both
 * here and in {@code ai_label_workspaces}: the reads are named for that, eligible rather than enabled.
 */
public interface TopicWorkspaceDAO {

    @SqlQuery("""
            SELECT t.workspace_id
            FROM topic_workspaces t
            JOIN ai_label_workspaces a ON a.workspace_id = t.workspace_id
            WHERE t.enabled = TRUE AND a.enabled = TRUE
            ORDER BY t.workspace_id
            """)
    List<String> eligibleWorkspaceIds();

    @SqlQuery("""
            SELECT COUNT(*) > 0
            FROM topic_workspaces t
            JOIN ai_label_workspaces a ON a.workspace_id = t.workspace_id
            WHERE t.workspace_id = :workspaceId AND t.enabled = TRUE AND a.enabled = TRUE
            """)
    boolean isEligible(@Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            INSERT INTO topic_workspaces (workspace_id, enabled, added_by)
            VALUES (:workspaceId, :enabled, :addedBy)
            ON DUPLICATE KEY UPDATE enabled = :enabled, added_by = :addedBy
            """)
    void upsert(@Bind("workspaceId") String workspaceId, @Bind("enabled") boolean enabled,
            @Bind("addedBy") String addedBy);
}
