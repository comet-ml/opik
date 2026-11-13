package com.comet.opik.domain.automations;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemSource;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.AutomationRun;
import com.comet.opik.api.automations.AutomationTestRequest;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.EffectiveFeedbackScore;
import com.comet.opik.domain.EntityFeedbackScores;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.ExperimentExecutionService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.domain.PromptService;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.domain.TraceEnrichmentOptions;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Runs automations for one batch of scored, production entities: the part of the routing consumer that
 * is generic over what gets written. Steps run in the order written; a failed step does not stop the
 * run; nothing is written twice to a target; and the daily limits pause an automation until midnight
 * UTC. Writes run as the system user, as every other background write does.
 *
 * <p>Redelivery is safe for the same reason it is for queue routing: the per-target history excludes what
 * a target already holds, so a retried batch cannot duplicate an item.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AutomationExecutor {

    public static final int MAX_CHAIN_DEPTH = 8;
    public static final String LIMIT_ITEMS = "max_items_per_day";
    public static final String LIMIT_RUNS = "max_runs_per_day";
    private static final int SAMPLING_HISTORY_DAYS = 7;
    private static final TraceEnrichmentOptions SUITE_CASE = TraceEnrichmentOptions.builder()
            .includeTags(true).includeMetadata(true).build();

    private final @NonNull AutomationService automationService;
    private final @NonNull AutomationRunService runService;
    private final @NonNull AnnotationQueueService annotationQueueService;
    private final @NonNull DatasetItemService datasetItemService;
    private final @NonNull AutomationTargetHistoryDAO targetHistoryDAO;
    private final @NonNull FeedbackScoreDAO feedbackScoreDAO;
    private final @NonNull TraceDAO traceDAO;
    private final @NonNull TraceThreadDAO traceThreadDAO;
    private final @NonNull TraceThreadService traceThreadService;
    private final @NonNull AutomationOriginStore originStore;
    private final @NonNull ExperimentExecutionService experimentExecutionService;
    private final @NonNull ExperimentService experimentService;
    private final @NonNull PromptService promptService;
    private final @NonNull GithubIntegrationService githubIntegrationService;
    private final @NonNull GithubClient githubClient;
    private final @NonNull WebhookHttpClient webhookHttpClient;

    /** The listener's guard: whether any automation of the project (or, with none given, the workspace) listens to scores. */
    public boolean listens(@NonNull String workspaceId, @Nullable UUID projectId) {
        return automationService.hasEnabledAutomation(workspaceId, projectId,
                Automation.Event.FEEDBACK_SCORE_CREATED.getValue());
    }

    /** Items written across every automation of the batch. */
    public Mono<Long> run(@NonNull String workspaceId, @NonNull AnnotationQueue.AnnotationScope scope,
            @NonNull Map<UUID, EntityFeedbackScores> production) {
        if (production.isEmpty()) {
            return Mono.just(0L);
        }
        Set<UUID> projectIds = production.values().stream().map(EntityFeedbackScores::projectId)
                .collect(Collectors.toSet());
        var triggerScope = scope == AnnotationQueue.AnnotationScope.THREAD
                ? Automation.Scope.THREAD
                : Automation.Scope.TRACE;

        return Mono.fromCallable(() -> automationService.findEnabledByProjects(workspaceId, projectIds,
                Automation.Event.FEEDBACK_SCORE_CREATED))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .filter(automation -> automation.pausedReason() == null)
                .concatMap(automation -> runOne(workspaceId, automation, triggerScope, production))
                .reduce(0L, Long::sum);
    }

    /**
     * Shadow run on one entity: the same decision, with the writes replaced by what they would do. The
     * per-day sample is reported as admitted, since a random draw tells the caller nothing. An entity
     * not logged by an SDK is reported as it would be treated: not production, so nothing fires.
     */
    public Mono<AutomationRun> test(@NonNull String workspaceId, @NonNull Automation automation,
            @NonNull AutomationTestRequest request) {
        var scope = request.threadId() != null ? Automation.Scope.THREAD : Automation.Scope.TRACE;
        Mono<UUID> entityId = request.threadId() != null
                ? traceThreadService.getThreadModelId(automation.projectId(), request.threadId())
                : Mono.just(request.traceId());

        return entityId
                .flatMap(id -> {
                    Mono<Set<UUID>> production = scope == Automation.Scope.THREAD
                            ? traceThreadDAO.getLoggingSourceIds(Set.of(automation.projectId()), Set.of(id))
                            : traceDAO.getLoggingSourceIds(Set.of(automation.projectId()), Set.of(id));
                    return production.flatMap(kept -> feedbackScoreDAO
                            .getEffectiveScores(AutomationService.entityTypeOf(scope), Set.of(id))
                            .collect(Collectors.toMap(EffectiveFeedbackScore::name, EffectiveFeedbackScore::value,
                                    (first, ignored) -> first))
                            .map(scores -> EntityFeedbackScores.builder().entityId(id)
                                    .projectId(automation.projectId())
                                    .scores(kept.contains(id) ? scores : Map.of()).build()));
                })
                .flatMap(entity -> test(workspaceId, automation, scope, entity))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }

    Mono<AutomationRun> test(String workspaceId, Automation automation, Automation.Scope scope,
            EntityFeedbackScores entity) {
        boolean matched = fires(automation, scope, entity.scores());
        return Flux.fromIterable(automation.steps())
                .concatMap(step -> matched
                        ? wouldWrite(workspaceId, automation, step, entity)
                        : Mono.just(testStep(step, AutomationRun.StepStatus.SKIPPED, "trigger did not match", null)))
                .collectList()
                .map(steps -> AutomationRun.builder()
                        .automationId(automation.id()).projectId(automation.projectId())
                        .kind(AutomationRun.Kind.SINGLE)
                        .status(matched ? AutomationRunService.statusOf(steps) : AutomationRun.Status.DONE)
                        .trigger(AutomationRun.Trigger.builder()
                                .event(Automation.Event.FEEDBACK_SCORE_CREATED.getValue()).scope(scope)
                                .fired(1).matched(matched ? 1 : 0).sampled(matched ? 1 : 0)
                                .itemId(entity.entityId())
                                .build())
                        .steps(steps)
                        .costUsd(BigDecimal.ZERO)
                        .childRunIds(List.of())
                        .startedAt(Instant.now()).finishedAt(Instant.now())
                        .build())
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }

    private Mono<Long> runOne(String workspaceId, Automation automation, Automation.Scope scope,
            Map<UUID, EntityFeedbackScores> production) {

        var inProject = production.values().stream()
                .filter(entity -> automation.projectId().equals(entity.projectId()))
                .toList();
        if (inProject.isEmpty()) {
            return Mono.just(0L);
        }

        var matched = inProject.stream()
                .filter(entity -> fires(automation, scope, entity.scores()))
                .map(EntityFeedbackScores::entityId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return Mono.fromCallable(() -> admit(workspaceId, automation, matched))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(admitted -> runSteps(workspaceId, automation, admitted)
                        .flatMap(outcomes -> Mono.fromCallable(() -> {
                            long written = outcomes.stream().mapToLong(AutomationRunService.StepOutcome::done).sum();
                            runService.record(workspaceId, AutomationRunService.Rollup.builder()
                                    .automationId(automation.id()).projectId(automation.projectId())
                                    .event(Automation.Event.FEEDBACK_SCORE_CREATED.getValue()).scope(scope)
                                    .fired(inProject.size()).matched(matched.size()).sampled(admitted.size())
                                    .steps(outcomes)
                                    .lastError(outcomes.stream().map(AutomationRunService.StepOutcome::lastError)
                                            .filter(Objects::nonNull).findFirst().orElse(null))
                                    .build());
                            enforceLimits(workspaceId, automation, admitted.size(), written);
                            return written;
                        }).subscribeOn(Schedulers.boundedElastic())));
    }

    private boolean fires(Automation automation, Automation.Scope scope, Map<String, BigDecimal> scores) {
        return automation.triggers().stream()
                .filter(trigger -> Automation.Event
                        .fromString(trigger.event()) == Automation.Event.FEEDBACK_SCORE_CREATED)
                .filter(trigger -> trigger.scope() == scope)
                .anyMatch(trigger -> automationService.matches(trigger, scores));
    }

    /**
     * What the per-day sample and the daily limits let through. The sample is a random draw at the rate
     * that would spread {@code per_day} over a typical day's matches, with a hard cap on top; the limits
     * are hard caps only.
     */
    private Set<UUID> admit(String workspaceId, Automation automation, Set<UUID> matched) {
        if (matched.isEmpty()) {
            return matched;
        }
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        var today = runService.stats(workspaceId, List.of(automation.id()), dayStart, 1)
                .getOrDefault(automation.id(), AutomationRunService.emptyStats(1));

        Integer perDay = automation.triggers().stream()
                .map(Automation.Trigger::perDay).filter(Objects::nonNull).findFirst().orElse(null);
        Set<UUID> admitted = matched;
        if (perDay != null) {
            long remaining = Math.max(0, perDay - today.sampled());
            var week = runService.stats(workspaceId, List.of(automation.id()),
                    Instant.now().minus(Duration.ofDays(SAMPLING_HISTORY_DAYS)), SAMPLING_HISTORY_DAYS)
                    .getOrDefault(automation.id(), AutomationRunService.emptyStats(SAMPLING_HISTORY_DAYS));
            double matchedPerDay = Math.max(perDay, (double) week.matched() / SAMPLING_HISTORY_DAYS);
            double rate = perDay / matchedPerDay;
            var random = ThreadLocalRandom.current();
            admitted = matched.stream()
                    .filter(id -> random.nextDouble() < rate)
                    .limit(remaining)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        var limits = automation.limits();
        if (limits != null && limits.maxRunsPerDay() != null) {
            long remaining = Math.max(0, limits.maxRunsPerDay() - today.sampled());
            admitted = admitted.stream().limit(remaining).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (limits != null && limits.maxItemsPerDay() != null) {
            long remaining = Math.max(0, limits.maxItemsPerDay() - today.itemsWritten());
            admitted = admitted.stream().limit(remaining).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return admitted;
    }

    private void enforceLimits(String workspaceId, Automation automation, long sampledNow, long writtenNow) {
        var limits = automation.limits();
        if (limits == null || (limits.maxItemsPerDay() == null && limits.maxRunsPerDay() == null)) {
            return;
        }
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        var today = runService.stats(workspaceId, List.of(automation.id()), dayStart, 1)
                .getOrDefault(automation.id(), AutomationRunService.emptyStats(1));
        Instant midnight = dayStart.plus(Duration.ofDays(1));
        if (limits.maxItemsPerDay() != null && today.itemsWritten() >= limits.maxItemsPerDay()) {
            automationService.pause(automation.id(), midnight, LIMIT_ITEMS);
        } else if (limits.maxRunsPerDay() != null && today.sampled() >= limits.maxRunsPerDay()) {
            automationService.pause(automation.id(), midnight, LIMIT_RUNS);
        }
    }

    private Mono<List<AutomationRunService.StepOutcome>> runSteps(String workspaceId, Automation automation,
            Set<UUID> admitted) {
        return Flux.fromIterable(automation.steps())
                .concatMap(step -> admitted.isEmpty()
                        ? Mono.just(AutomationRunService.StepOutcome.builder().id(step.id()).type(step.type()).build())
                        : write(workspaceId, automation, step, admitted)
                                .map(done -> AutomationRunService.StepOutcome.builder()
                                        .id(step.id()).type(step.type()).done(done).build())
                                .onErrorResume(error -> {
                                    log.warn("Automation step failed, automation '{}', step '{}', workspace '{}'",
                                            automation.id(), step.id(), workspaceId, error);
                                    return Mono.just(AutomationRunService.StepOutcome.builder()
                                            .id(step.id()).type(step.type()).failed(admitted.size())
                                            .lastError(error.getMessage()).build());
                                }))
                .collectList()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }

    private Mono<Long> write(String workspaceId, Automation automation, Automation.Step step, Set<UUID> itemIds) {
        return switch (Objects.requireNonNull(Automation.StepType.fromString(step.type()))) {
            case ANNOTATION_QUEUE_ADD -> annotationQueueService.addItems(step.annotationQueueId(), itemIds,
                    AnnotationQueueItemSource.AUTOMATED);
            case TEST_SUITE_ADD -> addToDataset(automation, step, step.testSuiteId(),
                    AutomationTargetHistoryDAO.TargetType.TEST_SUITE, itemIds);
            case DATASET_ADD -> addToDataset(automation, step, step.datasetId(),
                    AutomationTargetHistoryDAO.TargetType.DATASET, itemIds);
            case TEST_SUITE_RUN, GITHUB_CHECK, NOTIFY -> Mono.error(new IllegalStateException(
                    "step '%s' runs on event triggers only".formatted(step.type())));
        };
    }

    /** Items first, ledger second, for the reason the queue service gives: a missing ledger row costs a no-op, not an item. */
    private Mono<Long> addToDataset(Automation automation, Automation.Step step, UUID datasetId,
            AutomationTargetHistoryDAO.TargetType targetType, Set<UUID> traceIds) {
        return targetHistoryDAO.findPreviouslyAddedItems(targetType, datasetId, automation.projectId(), traceIds)
                .flatMap(alreadyAdded -> {
                    Set<UUID> eligible = traceIds.stream().filter(id -> !alreadyAdded.contains(id))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (eligible.isEmpty()) {
                        return Mono.just(0L);
                    }
                    return datasetItemService.createFromTraces(datasetId, eligible, SUITE_CASE,
                            step.assertion() == null ? null : List.of(step.assertion()), ExecutionPolicy.DEFAULT, null)
                            .then(targetHistoryDAO.recordItems(targetType, datasetId, automation.id(), eligible,
                                    automation.projectId()))
                            .thenReturn((long) eligible.size());
                });
    }

    private Mono<AutomationRun.Step> wouldWrite(String workspaceId, Automation automation, Automation.Step step,
            EntityFeedbackScores entity) {
        Set<UUID> one = Set.of(entity.entityId());
        Mono<Set<UUID>> held = switch (Objects.requireNonNull(Automation.StepType.fromString(step.type()))) {
            case ANNOTATION_QUEUE_ADD, TEST_SUITE_RUN, GITHUB_CHECK, NOTIFY -> Mono.just(Set.of());
            case TEST_SUITE_ADD -> targetHistoryDAO.findPreviouslyAddedItems(
                    AutomationTargetHistoryDAO.TargetType.TEST_SUITE, step.testSuiteId(), automation.projectId(), one);
            case DATASET_ADD -> targetHistoryDAO.findPreviouslyAddedItems(
                    AutomationTargetHistoryDAO.TargetType.DATASET, step.datasetId(), automation.projectId(), one);
        };
        return held.map(already -> already.isEmpty()
                ? testStep(step, AutomationRun.StepStatus.DONE, null, Map.of("would_add", entity.entityId()))
                : testStep(step, AutomationRun.StepStatus.SKIPPED, "already written by automation", null));
    }

    private static AutomationRun.Step testStep(Automation.Step step, AutomationRun.StepStatus status, String reason,
            Map<String, Object> output) {
        return AutomationRun.Step.builder()
                .id(step.id()).type(step.type()).status(status).reason(reason)
                .done(status == AutomationRun.StepStatus.DONE ? 1 : 0)
                .skipped(status == AutomationRun.StepStatus.SKIPPED ? 1 : 0)
                .failed(status == AutomationRun.StepStatus.FAILED ? 1 : 0)
                .output(output == null ? null : JsonUtils.valueToTree(output))
                .build();
    }

    // ---- event triggers: one firing, one run, steps that may wait on an experiment

    /** What the stream hands over: first resume anything waiting on this object, then fire what listens. */
    public Mono<Void> handle(@NonNull AutomationEvent message) {
        return resumeWaiting(message).then(fire(message));
    }

    private Mono<Void> resumeWaiting(AutomationEvent message) {
        if (!Automation.Event.EXPERIMENT_FINISHED.getValue().equals(message.event())) {
            return Mono.empty();
        }
        String waitingOn = waitKey(Automation.Event.EXPERIMENT_FINISHED, message.itemId());
        return Mono.fromCallable(() -> runService.findWaiting(message.workspaceId(), waitingOn))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .concatMap(
                        run -> Mono.fromCallable(() -> automationService.get(message.workspaceId(), run.automationId()))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(automation -> resume(message, automation, run))
                                .onErrorResume(error -> {
                                    log.warn("Failed to resume automation run '{}', workspace '{}'", run.id(),
                                            message.workspaceId(), error);
                                    return Mono.empty();
                                }))
                .then();
    }

    private Mono<Void> fire(AutomationEvent message) {
        Automation.Event event = Automation.Event.fromString(message.event());
        if (event == null || event.isScoreEvent()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> message.projectId() == null
                ? automationService.findEnabledByWorkspace(message.workspaceId(), event)
                : automationService.findEnabledByProjects(message.workspaceId(), Set.of(message.projectId()), event))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .filter(automation -> automation.pausedReason() == null)
                .filter(automation -> automation.triggers().stream().anyMatch(trigger -> fires(trigger, message)))
                .concatMap(automation -> runSingle(message, automation)
                        .onErrorResume(error -> {
                            log.warn("Automation run failed to start, automation '{}', workspace '{}'",
                                    automation.id(), message.workspaceId(), error);
                            return Mono.empty();
                        }))
                .then();
    }

    /** Whether a trigger's parameters match the event: the prompt, the suite or the repository it names. */
    static boolean fires(Automation.Trigger trigger, AutomationEvent message) {
        if (!message.event().equals(trigger.event())) {
            return false;
        }
        Map<String, Object> payload = message.payload();
        return switch (Objects.requireNonNull(Automation.Event.fromString(trigger.event()))) {
            case PROMPT_COMMITTED -> trigger.promptId() == null
                    || trigger.promptId().toString().equals(payload.get("prompt_id"));
            case EXPERIMENT_FINISHED -> trigger.testSuiteId() == null
                    || trigger.testSuiteId().toString().equals(payload.get("dataset_id"));
            case CI_RUN -> (trigger.repository() == null || trigger.repository().equals(payload.get("repository")))
                    && (trigger.branch() == null || trigger.branch().equals(payload.get("branch")));
            case FEEDBACK_SCORE_CREATED -> false;
        };
    }

    private Mono<Void> runSingle(AutomationEvent message, Automation automation) {
        if (message.depth() >= MAX_CHAIN_DEPTH) {
            // The chain is bounded here rather than at write time: a cycle is allowed to exist, it just
            // stops after this many links, and the refused firing is visible as a failed run.
            log.warn("Refusing automation '{}' at chain depth {}, workspace '{}'", automation.id(), message.depth(),
                    message.workspaceId());
            return Mono.fromRunnable(() -> {
                UUID runId = runService.startSingle(message.workspaceId(), automation, message.event(),
                        message.itemId(), message.payload(), message.originRunId(), message.depth());
                runService.finishSingle(message.workspaceId(), runId, AutomationRun.Status.FAILED, Map.of(), List.of(),
                        null, null, "chain depth %d reached".formatted(MAX_CHAIN_DEPTH));
            }).subscribeOn(Schedulers.boundedElastic()).then();
        }
        return Mono.fromCallable(() -> runService.startSingle(message.workspaceId(), automation, message.event(),
                message.itemId(), message.payload(), message.originRunId(), message.depth()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(runId -> executeFrom(new RunContext(message, automation, runId, new HashMap<>(),
                        new ArrayList<>()), 0));
    }

    /** Picks the run up where it stopped: the waiting step's outputs come from the event that arrived. */
    private Mono<Void> resume(AutomationEvent message, Automation automation, AutomationRun run) {
        Map<String, Object> outputs = run.outputs() == null
                ? new HashMap<>()
                : new HashMap<>(JsonUtils.treeToValue(run.outputs(), Map.class));
        List<AutomationRunService.StepOutcome> outcomes = new ArrayList<>(run.steps().stream()
                .map(step -> AutomationRunService.StepOutcome.builder().id(step.id()).type(step.type())
                        .done(step.done()).skipped(step.skipped()).failed(step.failed()).lastError(step.lastError())
                        .build())
                .toList());
        int index = Optional.ofNullable(run.resumeStep()).orElse(0);
        var step = automation.steps().get(index);
        var original = AutomationEvent.builder()
                .workspaceId(message.workspaceId()).workspaceName(message.workspaceName()).userName(message.userName())
                .projectId(automation.projectId()).event(run.trigger().event()).itemId(String.valueOf(run.itemId()))
                .payload(run.triggerPayload() == null
                        ? Map.of()
                        : JsonUtils.treeToValue(run.triggerPayload(), Map.class))
                .originRunId(run.parentRunId()).depth(run.depth())
                .build();
        var context = new RunContext(original, automation, run.id(), outputs, outcomes);
        return suiteRunFinished(context, step, message.payload())
                .flatMap(result -> {
                    record(context, step, result);
                    return executeFrom(context, index + 1);
                });
    }

    private record RunContext(AutomationEvent event, Automation automation, UUID runId, Map<String, Object> outputs,
            List<AutomationRunService.StepOutcome> outcomes) {
    }

    private record StepResult(AutomationRun.StepStatus status, Map<String, Object> outputs, @Nullable String reason,
            @Nullable String waitingOn) {
        static StepResult done(Map<String, Object> outputs) {
            return new StepResult(AutomationRun.StepStatus.DONE, outputs, null, null);
        }
        static StepResult skipped(String reason) {
            return new StepResult(AutomationRun.StepStatus.SKIPPED, Map.of(), reason, null);
        }
        static StepResult failed(String reason) {
            return new StepResult(AutomationRun.StepStatus.FAILED, Map.of(), reason, null);
        }
        static StepResult waiting(String waitingOn, Map<String, Object> outputs) {
            return new StepResult(AutomationRun.StepStatus.DONE, outputs, null, waitingOn);
        }
    }

    private Mono<Void> executeFrom(RunContext context, int index) {
        var steps = context.automation().steps();
        if (index >= steps.size()) {
            return finish(context, null, null);
        }
        var step = steps.get(index);
        Mono<StepResult> result = step.onlyIf() != null && !holds(step.onlyIf(), context.outputs())
                ? Mono.just(StepResult.skipped("only_if did not hold"))
                : runStep(context, step).onErrorResume(error -> {
                    log.warn("Automation step failed, automation '{}', step '{}', run '{}'",
                            context.automation().id(), step.id(), context.runId(), error);
                    return Mono.just(StepResult.failed(error.getMessage()));
                });
        return result.flatMap(outcome -> {
            if (outcome.waitingOn() != null) {
                // The step started something that finishes later; the run parks here and the object's
                // origin points back at it, so the finishing event resumes this run and inherits its depth.
                context.outcomes()
                        .add(AutomationRunService.StepOutcome.builder().id(step.id()).type(step.type()).build());
                outcome.outputs().forEach((key, value) -> context.outputs().put(step.id() + "." + key, value));
                return originStore.record(outcome.waitingOn().substring(outcome.waitingOn().indexOf(':') + 1),
                        new AutomationOriginStore.Origin(context.runId(), context.event().depth()))
                        .then(finish(context, index, outcome.waitingOn()));
            }
            record(context, step, outcome);
            return executeFrom(context, index + 1);
        });
    }

    private static void record(RunContext context, Automation.Step step, StepResult outcome) {
        context.outcomes().removeIf(previous -> previous.id().equals(step.id()));
        context.outcomes().add(AutomationRunService.StepOutcome.builder()
                .id(step.id()).type(step.type())
                .done(outcome.status() == AutomationRun.StepStatus.DONE ? 1 : 0)
                .skipped(outcome.status() == AutomationRun.StepStatus.SKIPPED ? 1 : 0)
                .failed(outcome.status() == AutomationRun.StepStatus.FAILED ? 1 : 0)
                .lastError(outcome.status() == AutomationRun.StepStatus.FAILED ? outcome.reason() : null)
                .build());
        outcome.outputs().forEach((key, value) -> context.outputs().put(step.id() + "." + key, value));
    }

    private Mono<Void> finish(RunContext context, @Nullable Integer resumeStep, @Nullable String waitingOn) {
        return Mono.fromRunnable(() -> {
            var status = waitingOn != null
                    ? AutomationRun.Status.PENDING
                    : AutomationRunService.statusOf(context.outcomes().stream()
                            .map(outcome -> AutomationRun.Step.builder().id(outcome.id()).type(outcome.type())
                                    .done(outcome.done()).skipped(outcome.skipped()).failed(outcome.failed()).build())
                            .toList());
            String lastError = context.outcomes().stream().map(AutomationRunService.StepOutcome::lastError)
                    .filter(Objects::nonNull).reduce((first, second) -> second).orElse(null);
            runService.finishSingle(context.event().workspaceId(), context.runId(), status, context.outputs(),
                    context.outcomes(), resumeStep, waitingOn, lastError);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /** Whether an only_if holds against the outputs produced so far; an output not produced does not hold. */
    static boolean holds(Automation.StepConditions conditions, Map<String, Object> outputs) {
        return conditions.groups().stream().anyMatch(group -> group.conditions().stream().allMatch(condition -> {
            Object value = outputs.get(condition.stepOutput());
            if (!(value instanceof Number number)) {
                return false;
            }
            int comparison = new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(condition.value()));
            return switch (condition.operator()) {
                case GREATER_THAN -> comparison > 0;
                case LESS_THAN -> comparison < 0;
                case EQUAL -> comparison == 0;
                case GREATER_THAN_OR_EQUAL -> comparison >= 0;
                case LESS_THAN_OR_EQUAL -> comparison <= 0;
            };
        }));
    }

    private Mono<StepResult> runStep(RunContext context, Automation.Step step) {
        return switch (Objects.requireNonNull(Automation.StepType.fromString(step.type()))) {
            case TEST_SUITE_RUN -> runSuite(context, step);
            case GITHUB_CHECK -> githubCheck(context, step);
            case NOTIFY -> notify(context, step);
            case ANNOTATION_QUEUE_ADD, TEST_SUITE_ADD, DATASET_ADD -> Mono.just(StepResult.skipped(
                    "step '%s' needs a scored trace; this run was fired by %s".formatted(step.type(),
                            context.event().event())));
        };
    }

    static String waitKey(Automation.Event event, String itemId) {
        return "%s:%s".formatted(event.getValue(), itemId);
    }

    /**
     * Starts the suite on the prompt version the event names (or the step's prompt), through the same
     * server-side execution the playground uses, and parks the run until the experiment finishes.
     */
    private Mono<StepResult> runSuite(RunContext context, Automation.Step step) {
        String workspaceId = context.event().workspaceId();
        Map<String, Object> payload = context.event().payload();
        String versionId = payload.get("version_id") instanceof String id ? id : null;
        return Mono.fromCallable(() -> {
            PromptVersion version = versionId != null
                    ? promptService.getVersionById(workspaceId, UUID.fromString(versionId))
                    : step.promptId() == null ? null : promptService.getLatestVersion(workspaceId, step.promptId());
            if (version == null) {
                throw new IllegalStateException("no prompt version to run the suite on: the event names none and "
                        + "the step has no prompt");
            }
            return version;
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(version -> {
                    var request = ExperimentExecutionRequest.builder()
                            .datasetId(step.testSuiteId())
                            .datasetName(step.testSuiteName())
                            .projectId(context.automation().projectId())
                            .prompts(List.of(ExperimentExecutionRequest.PromptVariant.builder()
                                    .model(step.model())
                                    .messages(messagesOf(version))
                                    .promptVersions(
                                            List.of(Experiment.PromptVersionLink.builder().id(version.id()).build()))
                                    .experimentName("%s · %s".formatted(context.automation().name(),
                                            Optional.ofNullable(version.commit()).orElse(version.id().toString())))
                                    .build()))
                            .promptVersions(List.of(Experiment.PromptVersionLink.builder().id(version.id()).build()))
                            .build();
                    return experimentExecutionService.createAndExecute(request, List.of())
                            .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                                    .put(RequestContext.WORKSPACE_NAME, context.event().workspaceName())
                                    .put(RequestContext.USER_NAME, context.event().userName())
                                    .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                            .map(response -> {
                                UUID experimentId = response.experiments().getFirst().experimentId();
                                Map<String, Object> outputs = new HashMap<>();
                                outputs.put("experiment_id", experimentId.toString());
                                outputs.put("prompt_version_id", version.id().toString());
                                return StepResult.waiting(waitKey(Automation.Event.EXPERIMENT_FINISHED,
                                        experimentId.toString()), outputs);
                            });
                });
    }

    /** A chat template is a JSON list of messages; anything else is one user message. */
    static List<ExperimentExecutionRequest.PromptVariant.Message> messagesOf(PromptVersion version) {
        if (version.templateStructure() == TemplateStructure.CHAT) {
            try {
                var node = JsonUtils.getJsonNodeFromString(version.template());
                if (node.isArray()) {
                    List<ExperimentExecutionRequest.PromptVariant.Message> messages = new ArrayList<>();
                    node.forEach(message -> messages.add(ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role(message.path("role").asText("user"))
                            .content(message.path("content"))
                            .build()));
                    return messages;
                }
            } catch (RuntimeException ignored) {
                // Not a message list after all: fall through to one user message.
            }
        }
        return List.of(ExperimentExecutionRequest.PromptVariant.Message.builder()
                .role("user").content(JsonUtils.valueToTree(version.template())).build());
    }

    /** The waiting step's outputs once the experiment finished: the pass rate, and the regressions against the previous run of the suite. */
    private Mono<StepResult> suiteRunFinished(RunContext context, Automation.Step step, Map<String, Object> finished) {
        UUID experimentId = UUID.fromString(String.valueOf(finished.get("experiment_id")));
        BigDecimal passRate = finished.get("pass_rate") == null
                ? null
                : new BigDecimal(String.valueOf(finished.get("pass_rate")));
        long passed = finished.get("passed_count") == null
                ? 0
                : Long.parseLong(String.valueOf(finished.get("passed_count")));
        long total = finished.get("total_count") == null
                ? 0
                : Long.parseLong(String.valueOf(finished.get("total_count")));
        return baseline(context, step, experimentId)
                .map(Optional::of).defaultIfEmpty(Optional.empty())
                .map(baseline -> {
                    Map<String, Object> outputs = new HashMap<>();
                    outputs.put("experiment_id", experimentId.toString());
                    outputs.put("pass_rate", passRate == null ? 0 : passRate.doubleValue());
                    outputs.put("passed_count", passed);
                    outputs.put("total_count", total);
                    long regressions = 0;
                    if (baseline.isPresent()) {
                        var previous = baseline.get();
                        outputs.put("baseline_experiment_id", previous.id().toString());
                        outputs.put("baseline_pass_rate",
                                previous.passRate() == null ? 0 : previous.passRate().doubleValue());
                        long previousPassed = previous.passedCount() == null ? 0 : previous.passedCount();
                        // A count, not a per-item diff: cases that passed before and fail now, as far as totals say.
                        regressions = Math.max(0, previousPassed - passed);
                    }
                    outputs.put("regressions", regressions);
                    return StepResult.done(outputs);
                });
    }

    /** The previous finished experiment on the same suite, if any. */
    private Mono<Experiment> baseline(RunContext context, Automation.Step step, UUID experimentId) {
        var criteria = ExperimentSearchCriteria.builder()
                .datasetId(step.testSuiteId())
                .entityType(EntityType.TRACE)
                .sortingFields(List
                        .of(SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()))
                .build();
        return experimentService.find(1, 10, criteria)
                .flatMapIterable(Experiment.ExperimentPage::content)
                .filter(experiment -> !experimentId.equals(experiment.id()))
                .filter(experiment -> experiment.passRate() != null)
                .next()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, context.event().workspaceId())
                        .put(RequestContext.USER_NAME, context.event().userName())
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE));
    }

    private Mono<StepResult> githubCheck(RunContext context, Automation.Step step) {
        Map<String, Object> payload = context.event().payload();
        String sha = payload.get("commit") instanceof String commit ? commit : null;
        String repository = step.repository() != null
                ? step.repository()
                : payload.get("repository") instanceof String repo ? repo : null;
        if (sha == null || repository == null) {
            return Mono.just(
                    StepResult.skipped("no commit in this run: a check needs a ci:run with a repository and a commit"));
        }
        String workspaceId = context.event().workspaceId();
        return Mono.fromCallable(() -> githubIntegrationService.token(workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(token -> {
                    if (token.isEmpty()) {
                        return Mono.just(StepResult.failed("no GitHub token for this workspace: set one at "
                                + "/v1/private/automations/integrations/github"));
                    }
                    Object regressions = context.outputs().entrySet().stream()
                            .filter(entry -> entry.getKey().endsWith(".regressions")).map(Map.Entry::getValue)
                            .findFirst().orElse(null);
                    Object passRate = context.outputs().entrySet().stream()
                            .filter(entry -> entry.getKey().endsWith(".pass_rate")).map(Map.Entry::getValue)
                            .findFirst().orElse(null);
                    boolean failing = regressions instanceof Number number && number.longValue() > 0;
                    String suiteError = context.outcomes().stream()
                            .filter(outcome -> Automation.StepType.TEST_SUITE_RUN.getValue().equals(outcome.type()))
                            .map(AutomationRunService.StepOutcome::lastError).filter(Objects::nonNull)
                            .findFirst().orElse(null);
                    String description = suiteError != null
                            ? "suite run failed: " + suiteError
                            : passRate == null
                                    ? "suite not run"
                                    : "pass rate %.0f%%, %s regressions".formatted(
                                            ((Number) passRate).doubleValue() * 100,
                                            regressions == null ? "0" : regressions.toString());
                    var status = GithubClient.CommitStatus.builder()
                            .repository(repository).sha(sha)
                            .state(suiteError != null ? "error" : failing ? "failure" : "success")
                            .context(Optional.ofNullable(step.context()).orElse("opik/" + context.automation().name()))
                            .description(
                                    description.length() > 140 ? description.substring(0, 137) + "..." : description)
                            .targetUrl(payload.get("run_url") instanceof String url ? url : null)
                            .build();
                    return githubClient.postCommitStatus(token.get(), status)
                            .thenReturn(StepResult.done(Map.of("state", status.state())));
                });
    }

    private Mono<StepResult> notify(RunContext context, Automation.Step step) {
        if (step.url() == null) {
            return Mono.just(StepResult.failed("notify needs a url"));
        }
        var kind = Optional.ofNullable(step.kind()).orElse(Automation.NotifyKind.WEBHOOK);
        Map<String, Object> summary = new HashMap<>();
        summary.put("automation_id", context.automation().id().toString());
        summary.put("automation", context.automation().name());
        summary.put("run_id", context.runId().toString());
        summary.put("event", context.event().event());
        summary.put("item_id", context.event().itemId());
        summary.put("outputs", context.outputs());
        String text = Optional.ofNullable(step.message()).orElse("%s fired on %s %s".formatted(
                context.automation().name(), context.event().event(), context.event().itemId()))
                + (context.outputs().isEmpty() ? "" : " · " + context.outputs());
        String json = kind == Automation.NotifyKind.SLACK
                ? JsonUtils.writeValueAsString(Map.of("text", text))
                : JsonUtils.writeValueAsString(summary);
        var event = WebhookEvent.<Map<String, Object>>builder()
                .id(UUID.randomUUID().toString())
                .eventType(AlertEventType.EXPERIMENT_FINISHED)
                .alertType(kind == Automation.NotifyKind.SLACK ? AlertType.SLACK : AlertType.GENERAL)
                .alertId(context.automation().id())
                .alertName(context.automation().name())
                .projectId(context.automation().projectId())
                .workspaceId(context.event().workspaceId())
                .workspaceName(context.event().workspaceName())
                .userName(context.event().userName())
                .payload(summary)
                .jsonPayload(json)
                .createdAt(Instant.now())
                .url(step.url())
                .build();
        return webhookHttpClient.sendWebhook(event)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, context.event().workspaceId()))
                .thenReturn(StepResult.done(Map.of()));
    }
}
