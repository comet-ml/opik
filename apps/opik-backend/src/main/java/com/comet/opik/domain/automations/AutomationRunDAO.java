package com.comet.opik.domain.automations;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import jakarta.annotation.Nullable;
import lombok.Builder;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Run rollups and their per-step counters, in MySQL. A rollup is one row per automation per hour, so the
 * writes are upserts that add to what is there; several consumers folding into one row is the point.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(AutomationRunDAO.RunRow.class)
@RegisterConstructorMapper(AutomationRunDAO.StepRow.class)
@RegisterConstructorMapper(AutomationRunDAO.StatsRow.class)
public interface AutomationRunDAO {

    @Builder(toBuilder = true)
    record RunRow(UUID id, String workspaceId, UUID automationId, UUID projectId, String kind, String status,
            Instant windowStart, Instant windowEnd, String triggerEvent, String triggerScope,
            @Nullable String itemId, @Nullable String triggerPayload, @Nullable String stepOutputs,
            @Nullable Integer resumeStep, @Nullable String waitingOn, int depth, long fired, long matched,
            long sampled, BigDecimal costUsd, @Nullable UUID parentRunId, @Nullable String lastError,
            Instant startedAt, Instant finishedAt) {
    }

    @Builder(toBuilder = true)
    record StepRow(UUID runId, String stepId, String stepType, int position, long done, long skipped, long failed,
            @Nullable String lastError) {
    }

    @Builder(toBuilder = true)
    record StatsRow(UUID automationId, long fired, long matched, long sampled, BigDecimal costUsd, long stepsDone,
            long stepsSkipped, long stepsFailed) {
    }

    String FILTERS = """
            WHERE workspace_id = :workspaceId
            <if(projectId)> AND project_id = :projectId <endif>
            <if(automationId)> AND automation_id = :automationId <endif>
            <if(parentRunId)> AND parent_run_id = :parentRunId <endif>
            <if(since)> AND window_start >= :since <endif>
            """;

    @SqlUpdate("""
            INSERT INTO automation_runs
                (id, workspace_id, automation_id, project_id, kind, status, window_start, window_end, trigger_event,
                 trigger_scope, fired, matched, sampled, cost_usd, parent_run_id, last_error, started_at, finished_at)
            VALUES (:run.id, :run.workspaceId, :run.automationId, :run.projectId, 'rollup', 'done', :run.windowStart,
                    :run.windowEnd, :run.triggerEvent, :run.triggerScope, :run.fired, :run.matched, :run.sampled,
                    :run.costUsd, :run.parentRunId, :run.lastError, :run.startedAt, :run.finishedAt) AS new
            ON DUPLICATE KEY UPDATE
                fired = automation_runs.fired + new.fired,
                matched = automation_runs.matched + new.matched,
                sampled = automation_runs.sampled + new.sampled,
                cost_usd = automation_runs.cost_usd + new.cost_usd,
                last_error = COALESCE(new.last_error, automation_runs.last_error),
                finished_at = new.finished_at
            """)
    void upsertRun(@BindMethods("run") RunRow run);

    @SqlUpdate("""
            INSERT INTO automation_run_steps (run_id, step_id, step_type, position, done, skipped, failed, last_error)
            VALUES (:step.runId, :step.stepId, :step.stepType, :step.position, :step.done, :step.skipped,
                    :step.failed, :step.lastError) AS new
            ON DUPLICATE KEY UPDATE
                done = automation_run_steps.done + new.done,
                skipped = automation_run_steps.skipped + new.skipped,
                failed = automation_run_steps.failed + new.failed,
                last_error = COALESCE(new.last_error, automation_run_steps.last_error)
            """)
    void upsertStep(@BindMethods("step") StepRow step);

    /** One row per firing, for event triggers. The window is the instant it started. */
    @SqlUpdate("""
            INSERT INTO automation_runs
                (id, workspace_id, automation_id, project_id, kind, status, window_start, window_end, trigger_event,
                 trigger_scope, item_id, trigger_payload, step_outputs, resume_step, waiting_on, depth,
                 fired, matched, sampled, cost_usd, parent_run_id, last_error, started_at, finished_at)
            VALUES (:run.id, :run.workspaceId, :run.automationId, :run.projectId, 'single', :run.status,
                    :run.windowStart, :run.windowEnd, :run.triggerEvent, :run.triggerScope, :run.itemId,
                    :run.triggerPayload, :run.stepOutputs, :run.resumeStep, :run.waitingOn, :run.depth,
                    :run.fired, :run.matched, :run.sampled, :run.costUsd, :run.parentRunId, :run.lastError,
                    :run.startedAt, :run.finishedAt)
            """)
    void insertSingle(@BindMethods("run") RunRow run);

    @SqlUpdate("""
            UPDATE automation_runs
            SET status = :status, step_outputs = :stepOutputs, resume_step = :resumeStep, waiting_on = :waitingOn,
                last_error = :lastError, finished_at = :finishedAt
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    void updateSingle(@Bind("workspaceId") String workspaceId, @Bind("id") UUID id, @Bind("status") String status,
            @Bind("stepOutputs") String stepOutputs, @Bind("resumeStep") Integer resumeStep,
            @Bind("waitingOn") String waitingOn, @Bind("lastError") String lastError,
            @Bind("finishedAt") Instant finishedAt);

    @SqlQuery("SELECT * FROM automation_runs WHERE workspace_id = :workspaceId AND waiting_on = :waitingOn")
    List<RunRow> findWaiting(@Bind("workspaceId") String workspaceId, @Bind("waitingOn") String waitingOn);

    /** Parked runs across workspaces whose wait handle starts with the prefix: what the poll job checks. */
    @SqlQuery("SELECT * FROM automation_runs WHERE status = 'pending' AND waiting_on LIKE CONCAT(:prefix, '%') LIMIT 500")
    List<RunRow> findWaitingByPrefix(@Bind("prefix") String prefix);

    @SqlUpdate("""
            UPDATE automation_runs
            SET status = 'failed', waiting_on = NULL, last_error = :reason, finished_at = :now
            WHERE status = 'pending' AND waiting_on IS NOT NULL AND started_at < :cutoff
            """)
    int failParkedBefore(@Bind("cutoff") Instant cutoff, @Bind("reason") String reason, @Bind("now") Instant now);

    @SqlQuery("SELECT * FROM automation_runs " + FILTERS + " ORDER BY window_start DESC LIMIT :limit OFFSET :offset")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<RunRow> find(@Bind("workspaceId") String workspaceId,
            @Define("projectId") @Bind("projectId") UUID projectId,
            @Define("automationId") @Bind("automationId") UUID automationId,
            @Define("parentRunId") @Bind("parentRunId") UUID parentRunId,
            @Define("since") @Bind("since") Instant since,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("SELECT COUNT(id) FROM automation_runs " + FILTERS)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Bind("workspaceId") String workspaceId,
            @Define("projectId") @Bind("projectId") UUID projectId,
            @Define("automationId") @Bind("automationId") UUID automationId,
            @Define("parentRunId") @Bind("parentRunId") UUID parentRunId,
            @Define("since") @Bind("since") Instant since);

    @SqlQuery("SELECT * FROM automation_runs WHERE workspace_id = :workspaceId AND id = :id")
    Optional<RunRow> findById(@Bind("workspaceId") String workspaceId, @Bind("id") UUID id);

    @SqlQuery("SELECT * FROM automation_run_steps WHERE run_id IN (<runIds>) ORDER BY run_id, position")
    List<StepRow> findStepsByRunIds(@BindList("runIds") List<UUID> runIds);

    @SqlQuery("SELECT id FROM automation_runs WHERE workspace_id = :workspaceId AND parent_run_id = :parentRunId")
    List<UUID> findChildIds(@Bind("workspaceId") String workspaceId, @Bind("parentRunId") UUID parentRunId);

    /** Per-automation totals since an instant, for the 7-day stats and the daily limits. */
    @SqlQuery("""
            SELECT r.automation_id,
                   SUM(r.fired) AS fired, SUM(r.matched) AS matched, SUM(r.sampled) AS sampled,
                   SUM(r.cost_usd) AS cost_usd,
                   COALESCE((SELECT SUM(s.done) FROM automation_run_steps s
                             JOIN automation_runs r2 ON r2.id = s.run_id
                             WHERE r2.automation_id = r.automation_id AND r2.window_start >= :since), 0) AS steps_done,
                   COALESCE((SELECT SUM(s.skipped) FROM automation_run_steps s
                             JOIN automation_runs r2 ON r2.id = s.run_id
                             WHERE r2.automation_id = r.automation_id AND r2.window_start >= :since), 0) AS steps_skipped,
                   COALESCE((SELECT SUM(s.failed) FROM automation_run_steps s
                             JOIN automation_runs r2 ON r2.id = s.run_id
                             WHERE r2.automation_id = r.automation_id AND r2.window_start >= :since), 0) AS steps_failed
            FROM automation_runs r
            WHERE r.workspace_id = :workspaceId
              AND r.automation_id IN (<automationIds>)
              AND r.window_start >= :since
            GROUP BY r.automation_id
            """)
    List<StatsRow> stats(@Bind("workspaceId") String workspaceId,
            @BindList("automationIds") List<UUID> automationIds,
            @Bind("since") Instant since);

    @SqlQuery("""
            SELECT MIN(window_start) FROM automation_runs
            WHERE workspace_id = :workspaceId AND project_id = :projectId
            """)
    Optional<Instant> firstRunAt(@Bind("workspaceId") String workspaceId, @Bind("projectId") UUID projectId);
}
