package com.comet.opik.domain.topics;

import org.jdbi.v3.sqlobject.config.KeyColumn;
import org.jdbi.v3.sqlobject.config.ValueColumn;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** The vectors tick's per-workspace watermark, {@code topic_workspaces.vectors_watermark}. */
interface TopicWorkspaceDAO {

    @SqlQuery("""
            SELECT workspace_id, vectors_watermark FROM topic_workspaces
            WHERE workspace_id IN (<workspaceIds>) AND vectors_watermark IS NOT NULL
            """)
    @KeyColumn("workspace_id")
    @ValueColumn("vectors_watermark")
    Map<String, Instant> vectorsWatermarks(@BindList("workspaceIds") Collection<String> workspaceIds);

    @SqlBatch("""
            INSERT INTO topic_workspaces (workspace_id, vectors_watermark) VALUES (:workspaceId, :watermark)
            ON DUPLICATE KEY UPDATE vectors_watermark = :watermark
            """)
    void setVectorsWatermarks(@Bind("workspaceId") List<String> workspaceIds,
            @Bind("watermark") List<Instant> watermarks);
}
