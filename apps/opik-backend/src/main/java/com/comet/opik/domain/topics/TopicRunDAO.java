package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.AbstractEnumColumnMapper;
import com.comet.opik.infrastructure.db.HasValue;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The attempt fields of a project's {@code topic_projects} row: the current or last run, first build, nightly update
 * or labelling pass. A run moves {@code queued -> running -> saved | failed}, or through {@code cancelling} to
 * {@code failed}; every statement here is a compare-and-set on the project, the run id and the status it expects, so
 * one writer wins and a run that lost its row writes nothing. {@code failed_runs} and {@code first_failed_at} count the
 * failed first builds and nightly updates since the last saved one: a labelling pass, a cancel that is not the run's
 * fault and a stopped pod's requeue do not count.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(TopicRunDAO.RunModeMapper.class)
@RegisterArgumentFactory(TopicRunDAO.RunErrorMapper.class)
@RegisterArgumentFactory(TopicRunDAO.CancelReasonMapper.class)
@RegisterColumnMapper(TopicRunDAO.RunModeMapper.class)
@RegisterColumnMapper(TopicRunDAO.RunStatusMapper.class)
@RegisterColumnMapper(TopicRunDAO.RunErrorMapper.class)
@RegisterConstructorMapper(InFlightRun.class)
@RegisterConstructorMapper(ListlessProject.class)
@RegisterConstructorMapper(TopicDAO.ProjectKey.class)
public interface TopicRunDAO {

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
    List<TopicDAO.ProjectKey> findLabelsDue(@BindList("workspaceIds") Collection<String> workspaceIds,
            @Bind("failedAfter") Instant failedAfter);

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

    class CancelReasonMapper extends AbstractEnumColumnMapper<TopicProject.CancelReason> {
        public CancelReasonMapper() {
            super(value -> byValue(TopicProject.CancelReason.values(), value), "topic_cancel_reason");
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
