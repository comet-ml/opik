package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.AbstractEnumColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code topic_runs}: a run is inserted {@code running} when it starts and moves once, to {@code saved} in the save
 * transaction or to {@code failed}. Only a {@code running} row moves. Also {@code topic_run_counts}, the counts a saved
 * run writes in its save transaction.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(TopicRunDAO.ModeMapper.class)
@RegisterArgumentFactory(TopicRunDAO.StatusMapper.class)
@RegisterColumnMapper(TopicRunDAO.ModeMapper.class)
@RegisterColumnMapper(TopicRunDAO.StatusMapper.class)
@RegisterConstructorMapper(TopicRun.class)
@RegisterConstructorMapper(TopicRunCount.class)
public interface TopicRunDAO {

    @SqlUpdate("""
            INSERT INTO topic_runs (run_id, workspace_id, project_id, mode, status, started_at, finished_at, error_code,
                error_message, stats)
            VALUES (:runId, :workspaceId, :projectId, :mode, :status, :startedAt, :finishedAt, :errorCode,
                :errorMessage, :stats)
            """)
    void insert(@BindMethods TopicRun run);

    @SqlUpdate("""
            UPDATE topic_runs SET status = 'saved', finished_at = :finishedAt, stats = :stats
            WHERE run_id = :runId AND status = 'running'
            """)
    int markSaved(@Bind("runId") UUID runId, @Bind("finishedAt") Instant finishedAt, @Bind("stats") String stats);

    @SqlUpdate("""
            UPDATE topic_runs SET status = 'failed', finished_at = :finishedAt, error_code = :errorCode,
                error_message = :errorMessage, stats = :stats
            WHERE run_id = :runId AND status = 'running'
            """)
    int markFailed(@Bind("runId") UUID runId, @Bind("finishedAt") Instant finishedAt,
            @Bind("errorCode") String errorCode, @Bind("errorMessage") String errorMessage,
            @Bind("stats") String stats);

    /**
     * The workspace of each run {@link #failAbandoned} is about to fail, one per run: none of them is counted yet, as a
     * run is counted by the write that ends its row. Locks the runs' rows, so the update in the same transaction fails
     * these.
     */
    @SqlQuery("""
            SELECT r.workspace_id FROM topic_runs r
            WHERE r.status = 'running' AND r.started_at < :startedBefore
            FOR UPDATE
            """)
    List<String> findAbandonedWorkspaceIds(@Bind("startedBefore") Instant startedBefore);

    /** Runs left {@code running} by a pod that died mid-run: failed with {@code abandoned}. */
    @SqlUpdate("""
            UPDATE topic_runs SET status = 'failed', finished_at = :now, error_code = 'abandoned',
                error_message = 'the run did not finish; its pod stopped or the lock expired'
            WHERE status = 'running' AND started_at < :startedBefore
            """)
    int failAbandoned(@Bind("startedBefore") Instant startedBefore, @Bind("now") Instant now);

    @SqlUpdate("DELETE FROM topic_runs WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_runs WHERE started_at < :before")
    int deleteStartedBefore(@Bind("before") Instant before);

    @SqlBatch("""
            INSERT INTO topic_run_counts (run_id, workspace_id, project_id, topic_id, traces, `rank`, saved_at)
            VALUES (:runId, :workspaceId, :projectId, :topicId, :traces, :rank, :savedAt)
            """)
    void insertCounts(@BindMethods List<TopicRunCount> counts);

    @SqlUpdate("DELETE FROM topic_run_counts WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteCountsByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_run_counts WHERE saved_at < :before")
    int deleteCountsSavedBefore(@Bind("before") Instant before);

    /**
     * The counts of the project's two latest saved runs by {@code saved_at} (then the greater run id), newest run
     * first; in a run, by rank, Other last. A project keeps at most 11 rows per run for the runs retention.
     */
    @SqlQuery("""
            SELECT c.* FROM topic_run_counts c
            JOIN (
                SELECT run_id, MAX(saved_at) AS last_saved_at FROM topic_run_counts
                WHERE workspace_id = :workspaceId AND project_id = :projectId
                GROUP BY run_id
                ORDER BY last_saved_at DESC, run_id DESC
                LIMIT 2
            ) last_runs ON last_runs.run_id = c.run_id
            ORDER BY last_runs.last_saved_at DESC, c.run_id DESC, c.`rank` IS NULL, c.`rank`
            """)
    List<TopicRunCount> findLastTwoRunCounts(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId);

    /** A project's counts, oldest run first; in a run, by rank, Other last. */
    @SqlQuery("""
            SELECT * FROM topic_run_counts WHERE project_id = :projectId
            ORDER BY saved_at, run_id, `rank` IS NULL, `rank`
            """)
    List<TopicRunCount> findCountsByProject(@Bind("projectId") UUID projectId);

    @SqlQuery("SELECT * FROM topic_runs WHERE run_id = :runId")
    TopicRun findById(@Bind("runId") UUID runId);

    @SqlQuery("SELECT * FROM topic_runs WHERE project_id = :projectId ORDER BY started_at, run_id")
    List<TopicRun> findByProject(@Bind("projectId") UUID projectId);

    /** The project's saved runs: the topics read API counts only their {@code trace_topics} rows and arrival's. */
    @SqlQuery("""
            SELECT run_id FROM topic_runs
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND status = 'saved'
            """)
    List<UUID> findSavedRunIds(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    class ModeMapper extends AbstractEnumColumnMapper<TopicRun.Mode> {
        public ModeMapper() {
            super(value -> Arrays.stream(TopicRun.Mode.values())
                    .filter(mode -> mode.getValue().equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown topic run mode '%s'".formatted(value))),
                    "topic_run_mode");
        }
    }

    class StatusMapper extends AbstractEnumColumnMapper<TopicRun.Status> {
        public StatusMapper() {
            super(value -> Arrays.stream(TopicRun.Status.values())
                    .filter(status -> status.getValue().equals(value))
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalArgumentException("Unknown topic run status '%s'".formatted(value))),
                    "topic_run_status");
        }
    }
}
