package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.PrototypeVectorsArgumentFactory;
import com.comet.opik.infrastructure.db.PrototypeVectorsColumnMapper;
import com.comet.opik.infrastructure.db.TopicCancelReasonMapper;
import com.comet.opik.infrastructure.db.TopicRunErrorMapper;
import com.comet.opik.infrastructure.db.TopicRunModeMapper;
import com.comet.opik.infrastructure.db.TopicRunStatusMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement on {@code topic_projects}, a project's current topics state, never its history. Each group of
 * columns has its own writer and is never set by another: the run fields (the current or last run, first build,
 * nightly update or labelling pass), the list fields (the saved list, written by a run's save), and the backfill fields
 * (the topic vectors tick). A run moves {@code queued -> running -> saved | failed}, or through {@code cancelling} to
 * {@code failed}; every run statement is a compare-and-set on the project, the run id and the status it expects, so
 * one writer wins and a run that lost its row writes nothing. {@code failed_runs} and {@code first_failed_at} count
 * the failed first builds and nightly updates since the last saved one: a labelling pass, a cancel that is not the
 * run's fault and a stopped pod's requeue do not count. A row without {@code backfill_started_at} has no backfill yet; ClickHouse stays the only source of
 * which traces have no vector.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(PrototypeVectorsArgumentFactory.class)
@RegisterArgumentFactory(TopicRunModeMapper.class)
@RegisterArgumentFactory(TopicRunErrorMapper.class)
@RegisterArgumentFactory(TopicCancelReasonMapper.class)
@RegisterColumnMapper(PrototypeVectorsColumnMapper.class)
@RegisterColumnMapper(TopicRunModeMapper.class)
@RegisterColumnMapper(TopicRunStatusMapper.class)
@RegisterColumnMapper(TopicRunErrorMapper.class)
@RegisterColumnMapper(TopicCancelReasonMapper.class)
@RegisterConstructorMapper(InFlightRun.class)
@RegisterConstructorMapper(ListlessProject.class)
@RegisterConstructorMapper(TopicProject.class)
@RegisterConstructorMapper(TopicProjectDAO.Backfill.class)
@RegisterConstructorMapper(TopicProjectKey.class)
public interface TopicProjectDAO {

    /**
     * A project's backfill columns.
     *
     * @param backfillStartedAt when this backfill began: the tick first saw the project, or reset the backfill because
     *        it did not run for the workspace for 2 x {@code topics.vectors.longWindowInterval}
     * @param backfillDrainedAt the start of the tick that drained the project since {@code backfillStartedAt}, null
     *        until one does. Drained: the tick read the project's whole backfill window, found fewer than
     *        {@code maxTracesPerTick} traces, and left none of them for want of time, a read, a back-off or an insert.
     *        So every trace with a done status in the window up to that instant, and a usable summary, had a vector
     *        when the tick ended, but for those the embed service refuses or answers with an invalid vector
     * @param backfillLastDoneAt the project's latest done status, as last discovered
     * @param backfillTracesTotal about how many traces of the project can get a vector: its traces created in the
     *        backfill window with a done status, as last discovered. Too high by the traces whose summary is blank or
     *        missing and by those the embed service refuses
     * @param backfillTracesDone about how many of them have a vector, as last discovered, plus the vectors the
     *        backfill wrote since. Never above {@code backfillTracesTotal}. Not kept up once the project is drained
     */
    @Builder(toBuilder = true)
    record Backfill(
            @NonNull String workspaceId,
            @NonNull UUID projectId,
            @NonNull Instant backfillStartedAt,
            @Nullable Instant backfillDrainedAt,
            @NonNull Instant backfillLastDoneAt,
            long backfillTracesTotal,
            long backfillTracesDone) {

        /** The traces still to embed, as the counts have them: approximate, never negative. */
        public long remaining() {
            return Math.max(0, backfillTracesTotal - backfillTracesDone);
        }
    }

    /** What one tick's backfill did for a project: drained it, or not ({@code drainedAt} null), and wrote vectors. */
    @Builder(toBuilder = true)
    record Progress(@NonNull String workspaceId, @NonNull UUID projectId, @Nullable Instant drainedAt, long written) {
    }

    @SqlUpdate("INSERT IGNORE INTO topic_projects (workspace_id, project_id) VALUES (:workspaceId, :projectId)")
    void ensureRow(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);

    /** Queues {@code runId} on the project's row, which must exist. 0 when the project has a run in flight. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_id = :runId, run_mode = :mode, run_status = 'queued', run_queued_at = :now, run_started_at = NULL,
                run_finished_at = NULL, deadline_at = NULL, heartbeat_at = NULL, cancel_reason = NULL,
                run_error_code = NULL
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            AND (run_status IS NULL OR run_status IN ('saved', 'failed'))
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

    /** {@code running -> queued}: its pod is stopping, so another pod runs it again. Not a failure. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_status = 'queued', run_queued_at = :now, run_started_at = NULL, deadline_at = NULL,
                heartbeat_at = NULL
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

    /**
     * Cancels the in-flight runs of the projects: queued ones fail at once, running ones go to {@code cancelling}.
     * For a project delete or an opt-out, which do not know the run ids.
     */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_finished_at = IF(run_status = 'queued', :now, run_finished_at),
                run_error_code = IF(run_status = 'queued', 'cancelled', run_error_code),
                cancel_reason = :reason,
                run_status = IF(run_status = 'queued', 'failed', 'cancelling')
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>) AND run_status IN ('queued', 'running')
            """)
    int cancelProjects(@Bind("workspaceId") String workspaceId, @BindList("projectIds") Collection<UUID> projectIds,
            @Bind("reason") TopicProject.CancelReason reason, @Bind("now") Instant now);

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
                run_finished_at = :now, run_status = 'failed'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status = 'cancelling'
            """)
    int endCancelled(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("now") Instant now);

    /** {@code running | cancelling -> failed} with {@code abandoned} when its heartbeat is older than {@code before}. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET run_error_code = 'abandoned',
                failed_runs = failed_runs + IF(run_mode = 'labels', 0, 1),
                first_failed_at = IF(run_mode = 'labels', first_failed_at, COALESCE(first_failed_at, run_started_at)),
                run_finished_at = :now, run_status = 'failed'
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND run_id = :runId
            AND run_status IN ('running', 'cancelling') AND heartbeat_at < :before
            """)
    int abandon(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId,
            @Bind("runId") UUID runId, @Bind("before") Instant before, @Bind("now") Instant now);

    /** In the save transaction: 0 when the run ended meanwhile, so the save rolls back. */
    @SqlUpdate("""
            UPDATE topic_projects
            SET failed_runs = IF(run_mode = 'labels', failed_runs, 0),
                first_failed_at = IF(run_mode = 'labels', first_failed_at, NULL),
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
     * The projects of {@code workspaceIds} a first build may be due for: no saved list, no run in flight, a trace
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
     * {@code expectedListRunId}, null for a first build. 0 when another run saved the project meanwhile, or the row is
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
    List<Backfill> findBackfills(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlQuery("""
            SELECT workspace_id, project_id, backfill_started_at, backfill_drained_at, backfill_last_done_at,
                backfill_traces_total, backfill_traces_done
            FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND backfill_drained_at IS NULL AND backfill_started_at IS NOT NULL
            """)
    List<Backfill> findUndrainedBackfills(@BindList("workspaceIds") Collection<String> workspaceIds);

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
    void startBackfills(@BindMethods List<Backfill> backfills);

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
    void discoverBackfills(@BindMethods List<Backfill> backfills);

    /** What a tick's backfill did: marks the drained projects and adds the vectors written, up to the total. */
    @SqlBatch("""
            UPDATE topic_projects
            SET backfill_drained_at = COALESCE(:drainedAt, backfill_drained_at),
                backfill_traces_done = LEAST(backfill_traces_done + :written,
                    GREATEST(backfill_traces_total, backfill_traces_done))
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    void recordBackfills(@BindMethods List<Progress> progress);

    /** The projects' whole rows: a deleted project has no topics state left. */
    @SqlUpdate("DELETE FROM topic_projects WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    /**
     * Removes the workspace's rows whose project no longer exists: a row a tick wrote for a project deleted meanwhile,
     * or one a failed delete on {@code ProjectsDeleted} left behind. A plain read finds them and the delete goes by
     * their ids, so nothing in {@code projects} is locked.
     */
    default int deleteOfMissingProjects(String workspaceId) {
        var missing = findOfMissingProjects(workspaceId);
        return missing.isEmpty() ? 0 : deleteByProjects(workspaceId, missing);
    }

    @SqlQuery("""
            SELECT t.project_id FROM topic_projects t
            LEFT JOIN projects p ON p.id = t.project_id AND p.workspace_id = t.workspace_id
            WHERE t.workspace_id = :workspaceId AND p.id IS NULL
            """)
    List<UUID> findOfMissingProjects(@Bind("workspaceId") String workspaceId);
}
