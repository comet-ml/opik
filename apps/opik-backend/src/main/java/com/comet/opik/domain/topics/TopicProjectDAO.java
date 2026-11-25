package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.PrototypeVectorsArgumentFactory;
import com.comet.opik.infrastructure.db.PrototypeVectorsColumnMapper;
import com.comet.opik.infrastructure.db.TopicRunErrorMapper;
import com.comet.opik.infrastructure.db.TopicRunModeMapper;
import com.comet.opik.infrastructure.db.TopicRunStatusMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import jakarta.annotation.Nullable;
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
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Every statement on {@code topic_projects}, a project's current topics state, never its history. Each group of
 * columns has its own writer and is never set by another: the run fields (the current or last run, discovery run or
 * nightly update), the list fields (the saved list, written by a run's save), and the backfill fields (the topic
 * vectors tick). A run starts {@code running} and moves once, to {@code saved} or {@code failed}; only a
 * {@code running} attempt moves. {@code failed_runs} and {@code first_failed_at} count the failed attempts since the
 * last saved one. A row without {@code backfill_started_at} has no backfill yet; ClickHouse stays the only source of
 * which traces have no vector.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(PrototypeVectorsArgumentFactory.class)
@RegisterArgumentFactory(TopicRunModeMapper.class)
@RegisterArgumentFactory(TopicRunErrorMapper.class)
@RegisterColumnMapper(PrototypeVectorsColumnMapper.class)
@RegisterColumnMapper(TopicRunModeMapper.class)
@RegisterColumnMapper(TopicRunStatusMapper.class)
@RegisterColumnMapper(TopicRunErrorMapper.class)
@RegisterConstructorMapper(TopicProject.class)
@RegisterConstructorMapper(TopicBackfill.class)
@RegisterConstructorMapper(TopicProjectKey.class)
public interface TopicProjectDAO {

    /**
     * Starts {@code runId}, creating the project's row when it has none. The caller holds the project's lock and has
     * recorded an attempt its pod left {@code running} first ({@link #recordAbandoned}). MySQL's count for the
     * upsert: 1 for a new row, 2 for an updated one, never 0.
     */
    @SqlUpdate("""
            INSERT INTO topic_projects (workspace_id, project_id, run_id, run_mode, run_status, run_started_at)
            VALUES (:workspaceId, :projectId, :runId, :mode, 'running', :startedAt)
            ON DUPLICATE KEY UPDATE run_id = :runId, run_mode = :mode, run_status = 'running',
                run_started_at = :startedAt, run_finished_at = NULL, run_error_code = NULL
            """)
    int startRun(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId, @Bind("runId") UUID runId,
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
     * Saves {@code runId}'s list on the project's row: a compare-and-set on {@code list_run_id}, which must still be
     * {@code expectedListRunId}, null for a discovery run. 0 when another run saved the project meanwhile, or the row is
     * gone. {@code saved_runs} counts every save; {@code first_built_at} keeps the first.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET saved_runs = saved_runs + 1, first_built_at = COALESCE(first_built_at, :savedAt),
                last_success_at = :savedAt, list_run_id = :runId, window_until = :windowUntil,
                labels_complete = FALSE, other_vectors = :otherVectors, embed_model = :embedModel
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND list_run_id <=> :expectedListRunId
            """)
    int saveList(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Nullable @Bind("expectedListRunId") UUID expectedListRunId, @Bind("runId") UUID runId,
            @Bind("savedAt") Instant savedAt, @Bind("windowUntil") Instant windowUntil,
            @Nullable @Bind("otherVectors") PrototypeVectors otherVectors, @Bind("embedModel") String embedModel);

    /**
     * A nightly save that left no live topic: the project has no list again, and a later discovery run makes a new
     * one. A compare-and-set on {@code list_run_id} as {@link #saveList}; the save still counts in {@code saved_runs}.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET saved_runs = saved_runs + 1, last_success_at = :savedAt, list_run_id = NULL, window_until = NULL,
                labels_complete = FALSE, other_vectors = NULL, embed_model = NULL
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND list_run_id <=> :expectedListRunId
            """)
    int resetList(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("expectedListRunId") UUID expectedListRunId, @Bind("savedAt") Instant savedAt);

    /**
     * Marks the labels of the project's saved list complete. Only while the row still names {@code runId}: a pass that
     * labelled an older run never marks a newer one. 0 when the row names another run or is gone.
     */
    @SqlUpdate("""
            UPDATE topic_projects SET labels_complete = TRUE
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND list_run_id = :runId
            """)
    int markLabelsComplete(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId);

    /** The projects with a saved list whose labels are not complete, the oldest save first. */
    @SqlQuery("""
            SELECT workspace_id, project_id FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND list_run_id IS NOT NULL AND labels_complete = FALSE
            ORDER BY last_success_at, project_id
            """)
    List<TopicProjectKey> findLabelsIncomplete(@BindList("workspaceIds") Collection<String> workspaceIds);

    @SqlQuery("SELECT * FROM topic_projects WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    List<TopicProject> findProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    /**
     * Of the projects of {@code workspaceIds}, those with no saved list and a trace written at or after {@code since}:
     * the ones a discovery run may be due for.
     */
    @SqlQuery("""
            SELECT p.workspace_id, p.id AS project_id FROM projects p
            WHERE p.workspace_id IN (<workspaceIds>) AND p.last_updated_trace_at >= :since
            AND NOT EXISTS (
                SELECT 1 FROM topic_projects s
                WHERE s.workspace_id = p.workspace_id AND s.project_id = p.id AND s.list_run_id IS NOT NULL
            )
            """)
    List<TopicProjectKey> findListlessProjects(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("since") Instant since);

    @SqlQuery("""
            SELECT workspace_id, project_id, backfill_started_at, backfill_drained_at, backfill_last_done_at,
                backfill_traces_total, backfill_traces_done
            FROM topic_projects
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>) AND backfill_started_at IS NOT NULL
            """)
    List<TopicBackfill> findBackfills(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlQuery("""
            SELECT workspace_id, project_id, backfill_started_at, backfill_drained_at, backfill_last_done_at,
                backfill_traces_total, backfill_traces_done
            FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND backfill_drained_at IS NULL AND backfill_started_at IS NOT NULL
            """)
    List<TopicBackfill> findUndrainedBackfills(@BindList("workspaceIds") Collection<String> workspaceIds);

    /**
     * Starts a backfill for each project that has none; a started one is left as it is. MySQL applies the assignments
     * in order, so {@code backfill_started_at} goes last: the others still see whether it was null.
     */
    @SqlBatch("""
            INSERT INTO topic_projects (workspace_id, project_id, backfill_started_at, backfill_last_done_at,
                backfill_traces_total, backfill_traces_done)
            VALUES (:workspaceId, :projectId, :backfillStartedAt, :backfillLastDoneAt, :backfillTracesTotal,
                :backfillTracesDone)
            ON DUPLICATE KEY UPDATE
                backfill_last_done_at = IF(backfill_started_at IS NULL, :backfillLastDoneAt, backfill_last_done_at),
                backfill_traces_total = IF(backfill_started_at IS NULL, :backfillTracesTotal, backfill_traces_total),
                backfill_traces_done = IF(backfill_started_at IS NULL, :backfillTracesDone, backfill_traces_done),
                backfill_started_at = COALESCE(backfill_started_at, :backfillStartedAt)
            """)
    void startBackfills(@BindMethods List<TopicBackfill> backfills);

    /**
     * A workspace the tick did not discover since {@code staleBefore}: its backfills start again, not drained and not
     * counted.
     */
    @SqlUpdate("""
            UPDATE topic_projects p
            JOIN topic_workspaces w ON w.workspace_id = p.workspace_id
            SET p.backfill_started_at = :now, p.backfill_drained_at = NULL, p.backfill_traces_total = 0,
                p.backfill_traces_done = 0
            WHERE p.workspace_id = :workspaceId AND p.backfill_started_at IS NOT NULL
            AND w.vectors_checked_at < :staleBefore
            """)
    int resetStaleBackfills(@Bind("workspaceId") String workspaceId, @Bind("staleBefore") Instant staleBefore,
            @Bind("now") Instant now);

    /** The projects a discovery found; a started backfill keeps its start and drained mark. */
    @SqlBatch("""
            INSERT INTO topic_projects (workspace_id, project_id, backfill_started_at, backfill_last_done_at,
                backfill_traces_total, backfill_traces_done)
            VALUES (:workspaceId, :projectId, :backfillStartedAt, :backfillLastDoneAt, :backfillTracesTotal,
                :backfillTracesDone)
            ON DUPLICATE KEY UPDATE
                backfill_last_done_at = :backfillLastDoneAt,
                backfill_traces_total = :backfillTracesTotal,
                backfill_traces_done = :backfillTracesDone,
                backfill_started_at = COALESCE(backfill_started_at, :backfillStartedAt)
            """)
    void discoverBackfills(@BindMethods List<TopicBackfill> backfills);

    /**
     * What a tick's backfill did: marks the drained projects and adds the vectors written, up to the total. Per
     * progress, the rows updated: 0 when the project's row is gone, its project deleted meanwhile.
     */
    @SqlBatch("""
            UPDATE topic_projects
            SET backfill_drained_at = COALESCE(:drainedAt, backfill_drained_at),
                backfill_traces_done = LEAST(backfill_traces_done + :written,
                    GREATEST(backfill_traces_total, backfill_traces_done))
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    int[] recordBackfills(@BindMethods List<TopicBackfillProgress> progress);

    /** The projects' whole rows: a deleted project has no topics state left. */
    @SqlUpdate("DELETE FROM topic_projects WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    /**
     * The workspace's rows whose project no longer exists: a row a tick wrote for a project deleted meanwhile, or one a
     * failed delete on {@code ProjectsDeleted} left behind. For the caller to delete by {@link #deleteByProjects} in
     * bounded batches. A plain read, so nothing in {@code projects} is locked.
     */
    @SqlQuery("""
            SELECT t.project_id FROM topic_projects t
            LEFT JOIN projects p ON p.id = t.project_id AND p.workspace_id = t.workspace_id
            WHERE t.workspace_id = :workspaceId AND p.id IS NULL
            """)
    List<UUID> findOfMissingProjects(@Bind("workspaceId") String workspaceId);
}
