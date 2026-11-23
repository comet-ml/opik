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

/** The vectors tick's per-workspace watermark, {@code topic_vector_watermarks}. */
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
}
