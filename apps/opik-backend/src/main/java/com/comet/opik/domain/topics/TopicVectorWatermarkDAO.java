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

/** The vectors tick's per-workspace watermark and long-window pass progress, {@code topic_vector_watermarks}. */
interface TopicVectorWatermarkDAO {

    @SqlQuery("SELECT workspace_id, watermark FROM topic_vector_watermarks WHERE workspace_id IN (<workspaceIds>)")
    @KeyColumn("workspace_id")
    @ValueColumn("watermark")
    Map<String, Instant> watermarks(@BindList("workspaceIds") Collection<String> workspaceIds);

    @SqlUpdate("""
            INSERT INTO topic_vector_watermarks (workspace_id, watermark) VALUES (:workspaceId, :watermark)
            ON DUPLICATE KEY UPDATE watermark = :watermark
            """)
    void set(@Bind("workspaceId") String workspaceId, @Bind("watermark") Instant watermark);

    @SqlQuery("""
            SELECT workspace_id, long_window_read_at FROM topic_vector_watermarks
            WHERE workspace_id IN (<workspaceIds>) AND long_window_read_at IS NOT NULL
            """)
    @KeyColumn("workspace_id")
    @ValueColumn("long_window_read_at")
    Map<String, Instant> longWindowReads(@BindList("workspaceIds") Collection<String> workspaceIds);

    /**
     * Only a workspace with a watermark row: one the pass reads before its first fresh read gets its long read again
     * on the pass's next tick.
     */
    @SqlUpdate("UPDATE topic_vector_watermarks SET long_window_read_at = :readAt WHERE workspace_id = :workspaceId")
    void setLongWindowRead(@Bind("workspaceId") String workspaceId, @Bind("readAt") Instant readAt);
}
