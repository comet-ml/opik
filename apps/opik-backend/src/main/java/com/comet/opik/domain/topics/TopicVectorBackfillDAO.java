package com.comet.opik.domain.topics;

import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** The topic vectors tick's discovery mark, {@code topic_workspaces.vectors_checked_at}. */
public interface TopicVectorBackfillDAO {

    /**
     * Marks the workspace's projects discovered at {@code now}, about once per {@code topics.vectors.longWindowInterval}.
     * Older than twice that: the tick has stopped for the workspace, and the projects' drained marks say nothing about
     * the traces since.
     */
    @SqlUpdate("""
            INSERT INTO topic_workspaces (workspace_id, vectors_checked_at) VALUES (:workspaceId, :now)
            ON DUPLICATE KEY UPDATE vectors_checked_at = :now
            """)
    void checked(@Bind("workspaceId") String workspaceId, @Bind("now") Instant now);

    /** When each of the workspaces was last discovered; a workspace never discovered has no entry. */
    @SqlQuery("SELECT workspace_id, vectors_checked_at FROM topic_workspaces WHERE workspace_id IN (<workspaceIds>)")
    @KeyColumn("workspace_id")
    @ValueColumn("vectors_checked_at")
    Map<String, Instant> findChecked(@BindList("workspaceIds") Collection<String> workspaceIds);
}
