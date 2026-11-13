package com.comet.opik.domain.automations;

import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.AutomationRun;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/** Run rollups: the executor folds each batch into the current hour's row; reads page and total them. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AutomationRunService {

    private final @NonNull TransactionTemplate transactionTemplate;

    /** One batch's outcome for one automation, folded into the hour it ran in. */
    @Builder(toBuilder = true)
    public record Rollup(@NonNull UUID automationId, @NonNull UUID projectId, @NonNull String event,
            @NonNull Automation.Scope scope, long fired, long matched, long sampled, @NonNull List<StepOutcome> steps,
            @Nullable String lastError) {
    }

    @Builder(toBuilder = true)
    public record StepOutcome(@NonNull String id, @NonNull String type, long done, long skipped, long failed,
            @Nullable String lastError) {
    }

    public void record(@NonNull String workspaceId, @NonNull Rollup rollup) {
        Instant now = Instant.now();
        Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);
        // Deterministic, so every consumer folding into this hour agrees on the row's id.
        UUID runId = UUID.nameUUIDFromBytes("%s:%s".formatted(rollup.automationId(), windowStart)
                .getBytes(StandardCharsets.UTF_8));

        var run = AutomationRunDAO.RunRow.builder()
                .id(runId).workspaceId(workspaceId).automationId(rollup.automationId()).projectId(rollup.projectId())
                .windowStart(windowStart).windowEnd(windowStart.plus(1, ChronoUnit.HOURS))
                .triggerEvent(rollup.event()).triggerScope(rollup.scope().getValue())
                .fired(rollup.fired()).matched(rollup.matched()).sampled(rollup.sampled())
                .costUsd(BigDecimal.ZERO).lastError(rollup.lastError())
                .startedAt(now).finishedAt(now)
                .build();

        transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRunDAO.class);
            dao.upsertRun(run);
            int position = 0;
            for (var step : rollup.steps()) {
                dao.upsertStep(AutomationRunDAO.StepRow.builder()
                        .runId(runId).stepId(step.id()).stepType(step.type()).position(position++)
                        .done(step.done()).skipped(step.skipped()).failed(step.failed()).lastError(step.lastError())
                        .build());
            }
            return null;
        });
    }

    public AutomationRun.AutomationRunPage find(@NonNull String workspaceId, @Nullable UUID projectId,
            @Nullable UUID automationId, @Nullable AutomationRun.Status status, @Nullable Instant since,
            @Nullable UUID parentRunId, int page, int size) {

        var found = transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRunDAO.class);
            long total = dao.count(workspaceId, projectId, automationId, parentRunId, since);
            var runs = dao.find(workspaceId, projectId, automationId, parentRunId, since, size, (page - 1) * size);
            var steps = runs.isEmpty()
                    ? List.<AutomationRunDAO.StepRow>of()
                    : dao.findStepsByRunIds(runs.stream().map(AutomationRunDAO.RunRow::id).toList());
            return Map.entry(total, toApi(runs, steps));
        });

        // Status is derived from the step counters, so it is applied to the page rather than the query.
        var content = found.getValue().stream()
                .filter(run -> status == null || run.status() == status)
                .toList();
        return AutomationRun.AutomationRunPage.builder()
                .content(content).page(page).size(content.size()).total(found.getKey()).build();
    }

    public Optional<AutomationRun> get(@NonNull String workspaceId, @NonNull UUID id) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRunDAO.class);
            return dao.findById(workspaceId, id).map(run -> toApi(List.of(run), dao.findStepsByRunIds(List.of(id)))
                    .getFirst().toBuilder().childRunIds(dao.findChildIds(workspaceId, id)).build());
        });
    }

    public Map<UUID, Automation.Stats> stats(@NonNull String workspaceId, @NonNull List<UUID> automationIds,
            @NonNull Instant since, int windowDays) {
        if (automationIds.isEmpty()) {
            return Map.of();
        }
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRunDAO.class).stats(workspaceId, automationIds, since))
                .stream()
                .collect(Collectors.toMap(AutomationRunDAO.StatsRow::automationId, row -> Automation.Stats.builder()
                        .windowDays(windowDays)
                        .fired(row.fired()).matched(row.matched()).sampled(row.sampled())
                        .stepsDone(row.stepsDone()).stepsSkipped(row.stepsSkipped()).stepsFailed(row.stepsFailed())
                        .itemsWritten(row.stepsDone())
                        .costUsd(row.costUsd())
                        .build()));
    }

    public Optional<Instant> firstRunAt(@NonNull String workspaceId, @NonNull UUID projectId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRunDAO.class).firstRunAt(workspaceId, projectId));
    }

    public static Automation.Stats emptyStats(int windowDays) {
        return Automation.Stats.builder().windowDays(windowDays).costUsd(BigDecimal.ZERO).build();
    }

    private static List<AutomationRun> toApi(List<AutomationRunDAO.RunRow> runs, List<AutomationRunDAO.StepRow> steps) {
        Map<UUID, List<AutomationRunDAO.StepRow>> stepsByRun = steps.stream()
                .collect(Collectors.groupingBy(AutomationRunDAO.StepRow::runId));
        return runs.stream().map(run -> {
            var runSteps = stepsByRun.getOrDefault(run.id(), List.of()).stream()
                    .map(step -> AutomationRun.Step.builder()
                            .id(step.stepId()).type(step.stepType())
                            .done(step.done()).skipped(step.skipped()).failed(step.failed())
                            .lastError(step.lastError())
                            .build())
                    .toList();
            return AutomationRun.builder()
                    .id(run.id()).automationId(run.automationId()).projectId(run.projectId())
                    .kind(AutomationRun.Kind.ROLLUP)
                    .status(statusOf(runSteps))
                    .trigger(AutomationRun.Trigger.builder()
                            .event(run.triggerEvent()).scope(Automation.Scope.fromString(run.triggerScope()))
                            .fired(run.fired()).matched(run.matched()).sampled(run.sampled())
                            .build())
                    .steps(runSteps)
                    .costUsd(run.costUsd())
                    .parentRunId(run.parentRunId())
                    .childRunIds(List.of())
                    .lastError(run.lastError())
                    .windowStart(run.windowStart()).windowEnd(run.windowEnd())
                    .startedAt(run.startedAt()).finishedAt(run.finishedAt())
                    .build();
        }).toList();
    }

    /** Failed only when every step failed; a run with any step done, or nothing to do, is done. */
    static AutomationRun.Status statusOf(List<AutomationRun.Step> steps) {
        long failed = steps.stream().filter(step -> step.failed() > 0).count();
        if (failed == 0) {
            return AutomationRun.Status.DONE;
        }
        boolean anyDone = steps.stream().anyMatch(step -> step.done() > 0);
        return anyDone ? AutomationRun.Status.PARTIAL : AutomationRun.Status.FAILED;
    }
}
