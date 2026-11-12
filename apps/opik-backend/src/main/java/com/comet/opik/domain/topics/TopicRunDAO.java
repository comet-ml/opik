package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.AbstractEnumColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code topic_runs}: a run is inserted {@code running} when it starts and moves once, to {@code saved} in the save
 * transaction or to {@code failed}. Only a {@code running} row moves.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(TopicRunDAO.ModeMapper.class)
@RegisterArgumentFactory(TopicRunDAO.StatusMapper.class)
@RegisterColumnMapper(TopicRunDAO.ModeMapper.class)
@RegisterColumnMapper(TopicRunDAO.StatusMapper.class)
@RegisterConstructorMapper(TopicRun.class)
@RegisterConstructorMapper(TopicRunDAO.FirstBuildFailures.class)
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
            WHERE workspace_id = :workspaceId AND run_id = :runId AND status = 'running'
            """)
    int markSaved(@Bind("workspaceId") String workspaceId, @Bind("runId") UUID runId,
            @Bind("finishedAt") Instant finishedAt, @Bind("stats") String stats);

    @SqlUpdate("""
            UPDATE topic_runs SET status = 'failed', finished_at = :finishedAt, error_code = :errorCode,
                error_message = :errorMessage, stats = :stats
            WHERE workspace_id = :workspaceId AND run_id = :runId AND status = 'running'
            """)
    int markFailed(@Bind("workspaceId") String workspaceId, @Bind("runId") UUID runId,
            @Bind("finishedAt") Instant finishedAt,
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

    /**
     * Of {@code projectIds}, those with a failed {@code first_build} run since their last saved run, {@code abandoned}
     * included: how many, when the first started and when the last ended. The first-build job's retries wait from the
     * last.
     */
    @SqlQuery("""
            SELECT r.project_id, COUNT(*) AS failures, MIN(r.started_at) AS first_started_at,
                MAX(r.finished_at) AS last_finished_at
            FROM topic_runs r
            WHERE r.workspace_id = :workspaceId AND r.project_id IN (<projectIds>)
            AND r.mode = 'first_build' AND r.status = 'failed'
            AND NOT EXISTS (
                SELECT 1 FROM topic_runs s
                WHERE s.workspace_id = r.workspace_id AND s.project_id = r.project_id AND s.status = 'saved'
                AND s.started_at > r.started_at
            )
            GROUP BY r.project_id
            """)
    List<FirstBuildFailures> findFirstBuildFailures(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlUpdate("DELETE FROM topic_runs WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    /** As {@link TopicDAO#deleteTopicsOfMissingProjects}, for {@code topic_runs}. */
    @SqlUpdate("""
            DELETE r FROM topic_runs r
            LEFT JOIN projects p ON p.id = r.project_id AND p.workspace_id = r.workspace_id
            WHERE r.workspace_id IN (<workspaceIds>) AND p.id IS NULL
            """)
    int deleteOfMissingProjects(@BindList("workspaceIds") Collection<String> workspaceIds);

    @SqlUpdate("DELETE FROM topic_runs WHERE started_at < :before")
    int deleteStartedBefore(@Bind("before") Instant before);

    /** The run, or {@code null} when the workspace has no run with this id. */
    @SqlQuery("SELECT * FROM topic_runs WHERE workspace_id = :workspaceId AND run_id = :runId")
    @Nullable TopicRun findById(@Bind("workspaceId") String workspaceId, @Bind("runId") UUID runId);

    @SqlQuery("""
            SELECT * FROM topic_runs
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            ORDER BY started_at, run_id
            """)
    List<TopicRun> findByProject(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    /** A project's failed first builds: how many, the first one's start and the last one's end. */
    record FirstBuildFailures(@NonNull UUID projectId, int failures, @NonNull Instant firstStartedAt,
            @NonNull Instant lastFinishedAt) {
    }

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
