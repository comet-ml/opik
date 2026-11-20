package com.comet.opik.domain.topics;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;

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
}
