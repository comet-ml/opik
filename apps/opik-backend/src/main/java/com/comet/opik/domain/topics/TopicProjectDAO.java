package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.PrototypeVectorsArgumentFactory;
import com.comet.opik.infrastructure.db.PrototypeVectorsColumnMapper;
import com.comet.opik.infrastructure.db.TopicCancelReasonMapper;
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
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement on {@code topic_projects}, a project's current topics state, never its history. Each group of
 * columns has its own writer and is never set by another: the run fields (the current or last run, discovery run,
 * nightly update or labelling pass), the list fields (the saved list, written by a run's save), the backfill fields
 * (the topic vectors tick), and the night fields {@code last_night} and {@code not_reached_nights} (the planner's night
 * pass, {@link TopicsNightPass}). A run moves {@code queued -> running -> saved | failed}, or through {@code cancelling} to
 * {@code failed}; every run statement is a compare-and-set on the project, the run id and the status it expects, so
 * one writer wins and a run that lost its row writes nothing. {@code failed_runs} and {@code first_failed_at} count
 * the failed discovery runs and nightly updates since the last saved one, and {@code last_failed_at} is when the last
 * of them failed: a labelling pass, a cancel that is not the run's fault and a stopped pod's requeue do not count. A row without {@code backfill_started_at} has no backfill yet; ClickHouse stays the only source of
 * which traces have no vector.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(PrototypeVectorsArgumentFactory.class)
@RegisterArgumentFactory(TopicRunModeMapper.class)
@RegisterArgumentFactory(TopicRunErrorMapper.class)
@RegisterArgumentFactory(TopicCancelReasonMapper.class)
@RegisterArgumentFactory(TopicRunStatusMapper.class)
@RegisterColumnMapper(PrototypeVectorsColumnMapper.class)
@RegisterColumnMapper(TopicRunModeMapper.class)
@RegisterColumnMapper(TopicRunStatusMapper.class)
@RegisterColumnMapper(TopicRunErrorMapper.class)
@RegisterColumnMapper(TopicCancelReasonMapper.class)
@RegisterConstructorMapper(InFlightRun.class)
@RegisterConstructorMapper(ListlessProject.class)
@RegisterConstructorMapper(TopicProject.class)
@RegisterConstructorMapper(TopicBackfill.class)
@RegisterConstructorMapper(TopicProjectKey.class)
public interface TopicProjectDAO {

    @SqlUpdate("INSERT IGNORE INTO topic_projects (workspace_id, project_id) VALUES (:workspaceId, :projectId)")
    void ensureRow(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    /**
     * Queues {@code runId} on the project's row, which must exist. 0 when the project has a run in flight, or its row
     * no longer fits the mode: a discovery run needs no saved list, a labelling pass a saved list with its labels not
     * complete, and a nightly update a saved list. The planner reads the rows before it queues, so a run that saved
     * the project in between makes this 0 instead of a run that fails at its save.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_id = :runId, run_mode = :mode, run_status = 'queued', run_queued_at = :now, run_started_at = NULL,
                run_finished_at = NULL, deadline_at = NULL, heartbeat_at = NULL, cancel_reason = NULL,
                run_error_code = NULL
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            AND (run_status IS NULL OR run_status IN ('saved', 'failed'))
            AND CASE :mode
                WHEN 'discovery' THEN list_run_id IS NULL
                WHEN 'labels' THEN list_run_id IS NOT NULL AND labels_complete = FALSE
                ELSE list_run_id IS NOT NULL
            END
            """)
    int queue(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId, @Bind("runId") UUID runId,
            @Bind("mode") TopicProject.RunMode mode, @Bind("now") Instant now);

    /** {@code queued -> running}: the pod that wins it runs the run. 0 when another pod did, or it was cancelled. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_status = 'running', run_started_at = :now, deadline_at = :deadline, heartbeat_at = :now
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'queued'
            """)
    int claim(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId, @Bind("runId") UUID runId,
            @Bind("now") Instant now, @Bind("deadline") Instant deadline);

    /** 0 when the run is no longer {@code running}: cancelled, abandoned or requeued. The run then stops. */
    @SqlUpdate("""
            UPDATE topic_projects SET heartbeat_at = :now
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'running'
            """)
    int heartbeat(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("now") Instant now);

    /**
     * {@code running -> queued}: its pod is stopping, so another pod runs it again. Not a failure. It keeps
     * {@code run_started_at}, so the run still counts as claimed ({@link #isClaimed}); the next claim sets it again.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_status = 'queued', run_queued_at = :now, deadline_at = NULL, heartbeat_at = NULL
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'running'
            """)
    int requeue(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("now") Instant now);

    /** Marks a queued run as published again, when it was last published before {@code queuedBefore}. */
    @SqlUpdate("""
            UPDATE topic_projects SET run_queued_at = :now
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'queued' AND run_queued_at < :queuedBefore
            """)
    int republish(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("queuedBefore") Instant queuedBefore, @Bind("now") Instant now);

    /** {@code running -> cancelling}: its pod sees it at its next heartbeat, stops and calls {@link #endCancelled}. */
    @SqlUpdate("""
            UPDATE topic_projects SET run_status = 'cancelling', cancel_reason = :reason
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'running'
            """)
    int cancel(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("reason") TopicProject.CancelReason reason);

    /** {@code queued -> failed} with {@code cancelled}: no pod runs it yet. Not counted. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_finished_at = :now, run_error_code = 'cancelled', cancel_reason = :reason, run_status = 'failed'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'queued'
            """)
    int dropQueued(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("reason") TopicProject.CancelReason reason, @Bind("now") Instant now);

    /** {@code queued -> failed} with {@code cancelled}, only when no pod ever claimed the run. Not counted. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_finished_at = :now, run_error_code = 'cancelled', run_status = 'failed'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'queued' AND run_started_at IS NULL
            """)
    int dropUnclaimed(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("now") Instant now);

    /**
     * {@code cancelling -> failed}, by the pod that stopped the run: {@code timeout} when the deadline cancelled it,
     * which counts, else {@code cancelled}, which does not.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_error_code = IF(cancel_reason = 'deadline', 'timeout', 'cancelled'),
                failed_runs = failed_runs + IF(cancel_reason = 'deadline' AND run_mode <> 'labels', 1, 0),
                first_failed_at = IF(cancel_reason = 'deadline' AND run_mode <> 'labels',
                    COALESCE(first_failed_at, run_started_at), first_failed_at),
                last_failed_at = IF(cancel_reason = 'deadline' AND run_mode <> 'labels', :now, last_failed_at),
                run_finished_at = :now, run_status = 'failed'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'cancelling'
            """)
    int endCancelled(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("now") Instant now);

    /**
     * {@code running | cancelling -> failed} with {@code abandoned} when its heartbeat is older than {@code before}, or
     * it has none: a row left running before the heartbeat column existed.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_error_code = 'abandoned',
                failed_runs = failed_runs + IF(run_mode = 'labels', 0, 1),
                first_failed_at = IF(run_mode = 'labels', first_failed_at, COALESCE(first_failed_at, run_started_at)),
                last_failed_at = IF(run_mode = 'labels', last_failed_at, :now),
                run_finished_at = :now, run_status = 'failed'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status IN ('running', 'cancelling') AND (heartbeat_at IS NULL OR heartbeat_at < :before)
            """)
    int abandon(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("before") Instant before, @Bind("now") Instant now);

    /** In the save transaction: 0 when the run ended meanwhile, so the save rolls back. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET failed_runs = IF(run_mode = 'labels', failed_runs, 0),
                first_failed_at = IF(run_mode = 'labels', first_failed_at, NULL),
                last_failed_at = IF(run_mode = 'labels', last_failed_at, NULL),
                run_finished_at = :finishedAt, run_status = 'saved'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'running'
            """)
    int markSaved(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("finishedAt") Instant finishedAt);

    /** 0 when the run is no longer {@code running}: it ended, or was cancelled, abandoned or requeued. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_error_code = :code,
                failed_runs = failed_runs + IF(run_mode = 'labels', 0, 1),
                first_failed_at = IF(run_mode = 'labels', first_failed_at, COALESCE(first_failed_at, run_started_at)),
                last_failed_at = IF(run_mode = 'labels', last_failed_at, :finishedAt),
                run_finished_at = :finishedAt, run_status = 'failed'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'running'
            """)
    int markFailed(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("finishedAt") Instant finishedAt,
            @Bind("code") TopicProject.RunError code);

    /** The run's status, empty unless the project's attempt is {@code runId}. */
    @SqlQuery("""
            SELECT run_status FROM topic_projects
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            """)
    Optional<TopicProject.RunStatus> findRunStatus(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId, @Bind("runId") UUID runId);

    /** True when the project's attempt is {@code runId} and a pod claimed it, even if it was requeued since. */
    @SqlQuery("""
            SELECT COUNT(*) > 0 FROM topic_projects
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
                AND run_started_at IS NOT NULL
            """)
    boolean isClaimed(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId);

    /** The run's error code, empty unless the project's attempt is {@code runId}, failed. */
    @SqlQuery("""
            SELECT run_error_code FROM topic_projects
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId AND run_status = 'failed'
            """)
    Optional<TopicProject.RunError> findRunError(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId, @Bind("runId") UUID runId);

    /** Every run in flight, of any workspace, and whether its project still exists. */
    @SqlQuery("""
            SELECT s.workspace_id, s.project_id, s.run_id, s.run_mode, s.run_status, s.run_queued_at, s.deadline_at,
                s.heartbeat_at, p.id IS NULL AS project_deleted
            FROM topic_projects s
            LEFT JOIN projects p ON p.workspace_id = s.workspace_id AND p.id = s.project_id
            WHERE s.run_status IN ('queued', 'running', 'cancelling')
            """)
    List<InFlightRun> findInFlight();

    /**
     * The projects of {@code workspaceIds} a discovery run may be due for: no saved list, no run in flight, a trace
     * written at or after {@code since}. With what the backfill gate and the retry policy read: the backfill and
     * failure columns of the project's row and its workspace's last vectors check, null when there is no row.
     */
    @SqlQuery("""
            SELECT p.workspace_id, p.id AS project_id, s.backfill_started_at, s.backfill_drained_at,
                COALESCE(s.backfill_traces_total, 0) AS backfill_traces_total,
                COALESCE(s.backfill_traces_done, 0) AS backfill_traces_done,
                COALESCE(s.failed_runs, 0) AS failed_runs, s.first_failed_at, s.run_finished_at,
                w.vectors_checked_at
            FROM projects p
            LEFT JOIN topic_projects s ON s.workspace_id = p.workspace_id AND s.project_id = p.id
            LEFT JOIN topic_workspaces w ON w.workspace_id = p.workspace_id
            WHERE p.workspace_id IN (<workspaceIds>) AND p.last_updated_trace_at >= :since
            AND s.list_run_id IS NULL
            AND (s.run_status IS NULL OR s.run_status IN ('saved', 'failed'))
            """)
    List<ListlessProject> findListless(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("since") Instant since);

    /**
     * The projects with a saved list whose labels are not complete and no run in flight, the oldest save first. A
     * project whose last labelling pass failed after {@code failedAfter} waits.
     */
    @SqlQuery("""
            SELECT workspace_id, project_id FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND list_run_id IS NOT NULL AND labels_complete = FALSE
            AND (run_status IS NULL OR run_status IN ('saved', 'failed'))
            AND NOT (run_mode <=> 'labels' AND run_status <=> 'failed' AND run_finished_at > :failedAfter)
            ORDER BY last_success_at, project_id
            """)
    List<TopicProjectKey> findLabelsDue(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("failedAfter") Instant failedAfter);

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
     * Forgets the project's saved list, so its next discovery run builds a new one. The run and backfill fields stay.
     * 0 when the project has no row, or its run is not in one of {@code ended}: a run in flight keeps the list.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET list_run_id = NULL, window_until = NULL, labels_complete = FALSE, other_vectors = NULL,
                embed_model = NULL
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            AND (run_status IS NULL OR run_status IN (<ended>))
            """)
    int resetList(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @BindList("ended") Collection<TopicProject.RunStatus> ended);

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
    int clearList(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
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

    @SqlQuery("SELECT * FROM topic_projects WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    List<TopicProject> findProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

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

    /**
     * The night's candidates of {@code workspaceIds}: the projects with a saved list that the night on {@code night}
     * has not looked at yet and that have no run in flight. The row the queue checks for an update is the source, so a
     * project with topics but no list is not a candidate. A row whose run is an update queued since {@code nightStart}
     * was the night's already, even if its {@code last_night} write failed: a failed one waits for the next night.
     */
    @SqlQuery("""
            SELECT * FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND list_run_id IS NOT NULL
            AND (last_night IS NULL OR last_night < :night)
            AND (run_status IS NULL OR run_status IN ('saved', 'failed'))
            AND NOT COALESCE(run_mode = 'update' AND run_queued_at >= :nightStart, FALSE)
            """)
    List<TopicProject> findNightDue(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("night") LocalDate night, @Bind("nightStart") Instant nightStart);

    /** The night looked at the projects: updated or skipped them, for any reason. */
    @SqlUpdate("""
            UPDATE topic_projects SET last_night = :night, not_reached_nights = 0
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)
            """)
    int markLooked(@Bind("workspaceId") String workspaceId, @BindList("projectIds") Collection<UUID> projectIds,
            @Bind("night") LocalDate night);

    /**
     * The projects of {@code workspaceIds} with a list that the night did not look at, that does not cover the night (a
     * list saved after the budget does), that the night did not queue an update for (as {@link #findNightDue}), and
     * whose not-reached streak does not count the night yet: the ones {@link #markNotReached} writes. The streak expression is the same as there.
     */
    @SqlQuery("""
            SELECT workspace_id, project_id FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND list_run_id IS NOT NULL
            AND (last_night IS NULL OR last_night < :night) AND (window_until IS NULL OR window_until < :night)
            AND not_reached_nights < GREATEST(1, DATEDIFF(:night,
                GREATEST(COALESCE(last_night, DATE(last_success_at), :night),
                    COALESCE(DATE(last_success_at), last_night, :night))))
            AND NOT COALESCE(run_mode = 'update' AND run_queued_at >= :nightStart, FALSE)
            """)
    List<TopicProjectKey> findNotReached(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("night") LocalDate night, @Bind("nightStart") Instant nightStart);

    /**
     * The night's budget ended before the projects: their not-reached streak counts it, once. The streak is the nights
     * since the last one that looked at the project, or since its last save when none did, at least 1. It comes from
     * the dates alone, so writing it again for the same night changes nothing. {@link #findNotReached} uses the same
     * expression.
     */
    @SqlUpdate("""
            UPDATE topic_projects SET not_reached_nights = GREATEST(1, DATEDIFF(:night,
                GREATEST(COALESCE(last_night, DATE(last_success_at), :night),
                    COALESCE(DATE(last_success_at), last_night, :night))))
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)
            AND (last_night IS NULL OR last_night < :night) AND (window_until IS NULL OR window_until < :night)
            """)
    int markNotReached(@Bind("workspaceId") String workspaceId, @BindList("projectIds") Collection<UUID> projectIds,
            @Bind("night") LocalDate night);

    /** How the night on {@code night} ended for the projects of {@code workspaceIds}, for its end log. */
    @SqlQuery("""
            SELECT COALESCE(SUM(last_night = :night), 0) AS looked,
                COALESCE(SUM(list_run_id IS NOT NULL AND (last_night IS NULL OR last_night < :night)
                    AND (window_until IS NULL OR window_until < :night)
                    AND NOT COALESCE(run_mode = 'update' AND run_queued_at >= :nightStart, FALSE)), 0) AS not_reached
            FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>)
            """)
    @RegisterConstructorMapper(NightCounts.class)
    NightCounts countNight(@BindList("workspaceIds") Collection<String> workspaceIds, @Bind("night") LocalDate night,
            @Bind("nightStart") Instant nightStart);

    /** The projects a night looked at, and the projects with a list it did not reach. */
    record NightCounts(long looked, long notReached) {
    }

    /**
     * {@code queued -> running} for a nightly update, only while the project still has a list that does not cover the
     * night: its {@code window_until} is before {@code windowTo}, the night's start. 0 otherwise, as {@link #claim}.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_status = 'running', run_started_at = :now, deadline_at = :deadline, heartbeat_at = :now
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'queued' AND list_run_id IS NOT NULL
            AND (window_until IS NULL OR window_until < :windowTo)
            """)
    int claimUpdate(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("now") Instant now, @Bind("deadline") Instant deadline,
            @Bind("windowTo") Instant windowTo);

    /**
     * {@code queued -> failed} with {@code cancelled} for a nightly update whose project has no list any more, or whose
     * list already covers the night (saved since it was queued). Not counted in {@code failed_runs}: it ran nothing.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_finished_at = :now, run_error_code = 'cancelled', run_status = 'failed'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'queued' AND (list_run_id IS NULL OR window_until >= :windowTo)
            """)
    int skipUpdate(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("now") Instant now, @Bind("windowTo") Instant windowTo);

    /** The workspaces among {@code workspaceIds} that have a row: those with Topics data, for retention. */
    @SqlQuery("SELECT DISTINCT workspace_id FROM topic_projects WHERE workspace_id IN (<workspaceIds>)")
    List<String> findWorkspacesWithProjects(@BindList("workspaceIds") Collection<String> workspaceIds);

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
