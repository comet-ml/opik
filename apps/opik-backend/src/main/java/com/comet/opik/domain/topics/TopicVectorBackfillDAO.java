package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code topic_vector_backfills}. Only the topic vectors tick writes it; a project delete removes the project's row.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(TopicVectorBackfill.class)
public interface TopicVectorBackfillDAO {

    String COLUMNS = """
            workspace_id, project_id, started_at, checked_at, drained_at, last_done_at, traces_total, traces_done
            """;

    /** Every row of {@code workspaceIds}. A project the tick has not seen yet has none. */
    @SqlQuery("SELECT " + COLUMNS + " FROM topic_vector_backfills WHERE workspace_id IN (<workspaceIds>)")
    List<TopicVectorBackfill> findByWorkspaceIds(
            @BindList(value = "workspaceIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<String> workspaceIds);

    /** The rows of {@code projectIds} in one workspace. */
    @SqlQuery("SELECT " + COLUMNS + """
            FROM topic_vector_backfills
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)
            """)
    List<TopicVectorBackfill> findByKeys(@Bind("workspaceId") String workspaceId,
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);

    /** The rows of {@code workspaceIds} not drained yet. */
    @SqlQuery("SELECT " + COLUMNS + """
            FROM topic_vector_backfills
            WHERE workspace_id IN (<workspaceIds>) AND drained_at IS NULL
            """)
    List<TopicVectorBackfill> findUndrained(
            @BindList(value = "workspaceIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<String> workspaceIds);

    /** Starts a backfill for each project that has no row; an existing row is left as it is. */
    @SqlBatch("""
            INSERT INTO topic_vector_backfills (workspace_id, project_id, started_at, checked_at, last_done_at,
                traces_total, traces_done)
            VALUES (:workspaceId, :projectId, :startedAt, :checkedAt, :lastDoneAt, :tracesTotal, :tracesDone)
            ON DUPLICATE KEY UPDATE workspace_id = workspace_id
            """)
    void start(@BindMethods List<TopicVectorBackfill> backfills);

    /**
     * A workspace re-enabled after the tick did not run for it since {@code staleBefore}: its rows start again, not
     * drained and not counted.
     */
    @SqlUpdate("""
            UPDATE topic_vector_backfills
            SET started_at = :now, checked_at = :now, drained_at = NULL, traces_total = 0, traces_done = 0
            WHERE workspace_id = :workspaceId AND checked_at < :staleBefore
            """)
    int resetStale(@Bind("workspaceId") String workspaceId, @Bind("staleBefore") Instant staleBefore,
            @Bind("now") Instant now);

    /** The tick discovered the workspace's projects at {@code now}: every row of it is checked. */
    @SqlUpdate("UPDATE topic_vector_backfills SET checked_at = :now WHERE workspace_id = :workspaceId")
    int touch(@Bind("workspaceId") String workspaceId, @Bind("now") Instant now);

    /**
     * The projects a discovery found: a new row starts a backfill, an existing one takes the new counts and latest done
     * status and keeps {@code started_at} and {@code drained_at}.
     */
    @SqlBatch("""
            INSERT INTO topic_vector_backfills (workspace_id, project_id, started_at, checked_at, last_done_at,
                traces_total, traces_done)
            VALUES (:workspaceId, :projectId, :startedAt, :checkedAt, :lastDoneAt, :tracesTotal, :tracesDone)
            ON DUPLICATE KEY UPDATE
                checked_at = :checkedAt,
                last_done_at = :lastDoneAt,
                traces_total = :tracesTotal,
                traces_done = :tracesDone
            """)
    void discovered(@BindMethods List<TopicVectorBackfill> backfills);

    /** What a tick's backfill did: marks the drained projects and adds the vectors written, up to the total. */
    @SqlBatch("""
            UPDATE topic_vector_backfills
            SET drained_at = COALESCE(:drainedAt, drained_at),
                traces_done = LEAST(traces_done + :written, GREATEST(traces_total, traces_done))
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    void record(@BindMethods List<TopicVectorBackfill.Progress> progress);

    /** Removes the rows of deleted projects. */
    @SqlUpdate("DELETE FROM topic_vector_backfills WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteByProjects(@Bind("workspaceId") String workspaceId,
            @BindList(value = "projectIds", onEmpty = BindList.EmptyHandling.NULL_STRING) Collection<UUID> projectIds);
}
