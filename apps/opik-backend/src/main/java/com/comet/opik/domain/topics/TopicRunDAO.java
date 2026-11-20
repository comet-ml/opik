package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.AbstractEnumColumnMapper;
import com.comet.opik.infrastructure.db.HasValue;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The attempt fields of a project's {@code topic_projects} row: the current or last run, first build or nightly
 * update. A run starts {@code running} and moves once, to {@code saved} or {@code failed}; only a {@code running}
 * attempt moves. {@code failed_runs} and {@code first_failed_at} count the failed attempts since the last saved one.
 * Each run logs itself; the row keeps no history.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(TopicRunDAO.RunModeMapper.class)
@RegisterArgumentFactory(TopicRunDAO.RunErrorMapper.class)
public interface TopicRunDAO {

    /**
     * Starts {@code runId}, creating the project's row when it has none. The caller holds the project's lock and has
     * recorded an attempt its pod left {@code running} first ({@link #recordAbandoned}).
     */
    @SqlUpdate("""
            INSERT INTO topic_projects (workspace_id, project_id, run_id, run_mode, run_status, run_started_at)
            VALUES (:workspaceId, :projectId, :runId, :mode, 'running', :startedAt)
            ON DUPLICATE KEY UPDATE run_id = :runId, run_mode = :mode, run_status = 'running',
                run_started_at = :startedAt, run_finished_at = NULL, run_error_code = NULL
            """)
    void start(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId, @Bind("runId") UUID runId,
            @Bind("mode") TopicProject.RunMode mode, @Bind("startedAt") Instant startedAt);

    /** In the save transaction: 0 when the run ended meanwhile, by its timeout, so the save rolls back. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_status = 'saved', run_finished_at = :finishedAt, failed_runs = 0, first_failed_at = NULL
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'running'
            """)
    int markSaved(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("finishedAt") Instant finishedAt);

    /** 0 when the run had ended already: saved, or failed by its own thread or its timeout. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_status = 'failed', run_finished_at = :finishedAt, run_error_code = :code,
                failed_runs = failed_runs + 1, first_failed_at = COALESCE(first_failed_at, run_started_at)
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'running'
            """)
    int markFailed(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("finishedAt") Instant finishedAt,
            @Bind("code") TopicProject.RunError code);

    /**
     * Records the project's attempt as failed with {@code abandoned} when it is still {@code running} and started
     * before {@code startedBefore}, the project's lock lease ago: its pod died mid-run. 1 when it did, for the caller
     * to count.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_status = 'failed', run_finished_at = :now, run_error_code = 'abandoned',
                failed_runs = failed_runs + 1, first_failed_at = COALESCE(first_failed_at, run_started_at)
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_status = 'running'
            AND run_started_at < :startedBefore
            """)
    int recordAbandoned(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("startedBefore") Instant startedBefore, @Bind("now") Instant now);

    /**
     * Of {@code projectIds}, those whose last {@code failures} attempts or more failed in a row, the latest started at
     * or after {@code since}: the night pauses them. An attempt still {@code running} that started before
     * {@code abandonedBefore} counts as failed: its pod died, and only the project's next attempt records it.
     */
    @SqlQuery("""
            SELECT project_id FROM topic_projects
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>) AND run_started_at >= :since
            AND failed_runs + (run_status = 'running' AND run_started_at < :abandonedBefore) >= :failures
            """)
    Set<UUID> findPausedProjectIds(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds, @Bind("failures") int failures,
            @Bind("since") Instant since, @Bind("abandonedBefore") Instant abandonedBefore);

    class RunModeMapper extends AbstractEnumColumnMapper<TopicProject.RunMode> {
        public RunModeMapper() {
            super(value -> byValue(TopicProject.RunMode.values(), value), "topic_run_mode");
        }
    }

    class RunStatusMapper extends AbstractEnumColumnMapper<TopicProject.RunStatus> {
        public RunStatusMapper() {
            super(value -> byValue(TopicProject.RunStatus.values(), value), "topic_run_status");
        }
    }

    class RunErrorMapper extends AbstractEnumColumnMapper<TopicProject.RunError> {
        public RunErrorMapper() {
            super(value -> byValue(TopicProject.RunError.values(), value), "topic_run_error");
        }
    }

    private static <T extends HasValue> T byValue(T[] values, String value) {
        return Arrays.stream(values)
                .filter(candidate -> candidate.getValue().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown value '%s'".formatted(value)));
    }
}
