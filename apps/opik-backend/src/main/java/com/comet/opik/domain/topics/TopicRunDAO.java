package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.AbstractEnumColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
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

    @SqlQuery("SELECT * FROM topic_runs WHERE run_id = :runId")
    TopicRun findById(@Bind("runId") UUID runId);

    @SqlQuery("SELECT * FROM topic_runs WHERE project_id = :projectId ORDER BY started_at, run_id")
    List<TopicRun> findByProject(@Bind("projectId") UUID projectId);

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
