package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;
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
 * The topic vectors backfill's state: the {@code backfill_*} columns of {@code topic_projects} and
 * {@code topic_workspaces.vectors_checked_at}. A project's row holds its current topics state, never its history, and
 * each writer sets only its own columns: the other columns belong to other writers and are never set here. A project
 * row without {@code backfill_started_at} has no backfill yet. ClickHouse stays the only source of which traces have
 * no vector.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(TopicVectorBackfillDAO.Backfill.class)
public interface TopicVectorBackfillDAO {

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

    @SqlQuery("""
            SELECT workspace_id, project_id, backfill_started_at, backfill_drained_at, backfill_last_done_at,
                backfill_traces_total, backfill_traces_done
            FROM topic_projects
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>) AND backfill_started_at IS NOT NULL
            """)
    List<Backfill> findByKeys(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    @SqlQuery("""
            SELECT workspace_id, project_id, backfill_started_at, backfill_drained_at, backfill_last_done_at,
                backfill_traces_total, backfill_traces_done
            FROM topic_projects
            WHERE workspace_id IN (<workspaceIds>) AND backfill_drained_at IS NULL AND backfill_started_at IS NOT NULL
            """)
    List<Backfill> findUndrained(@BindList("workspaceIds") Collection<String> workspaceIds);

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
    void start(@BindMethods List<Backfill> backfills);

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
    int resetStale(@Bind("workspaceId") String workspaceId, @Bind("staleBefore") Instant staleBefore,
            @Bind("now") Instant now);

    /**
     * Marks the workspace's projects discovered at {@code now}, about once per {@code topics.vectors.longWindowInterval}.
     * Older than twice that: the tick has stopped for the workspace, and the projects' drained marks say nothing about
     * the traces since.
     */
    @SqlUpdate("""
            INSERT INTO topic_workspaces (workspace_id, vectors_checked_at) VALUES (:workspaceId, :now)
            ON DUPLICATE KEY UPDATE vectors_checked_at = :now
            """)
    void checked(@Bind("workspaceId") String workspaceId, @Bind("now") Instant now);

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
    void discovered(@BindMethods List<Backfill> backfills);

    /** What a tick's backfill did: marks the drained projects and adds the vectors written, up to the total. */
    @SqlBatch("""
            UPDATE topic_projects
            SET backfill_drained_at = COALESCE(:drainedAt, backfill_drained_at),
                backfill_traces_done = LEAST(backfill_traces_done + :written,
                    GREATEST(backfill_traces_total, backfill_traces_done))
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    void record(@BindMethods List<Progress> progress);

    /** The projects' whole rows: a deleted project has no topics state left. */
    @SqlUpdate("DELETE FROM topic_projects WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)")
    int deleteByProjects(@Bind("workspaceId") String workspaceId,
            @BindList("projectIds") Collection<UUID> projectIds);

    /**
     * Removes the workspace's rows whose project no longer exists: a row a tick wrote for a project deleted meanwhile,
     * or one a failed delete on {@code ProjectsDeleted} left behind.
     */
    @SqlUpdate("""
            DELETE t FROM topic_projects t
            LEFT JOIN projects p ON p.id = t.project_id AND p.workspace_id = t.workspace_id
            WHERE t.workspace_id = :workspaceId AND p.id IS NULL
            """)
    int deleteOfMissingProjects(@Bind("workspaceId") String workspaceId);
}
