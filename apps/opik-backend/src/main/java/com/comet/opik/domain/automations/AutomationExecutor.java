package com.comet.opik.domain.automations;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemSource;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.AutomationRun;
import com.comet.opik.api.automations.AutomationTestRequest;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.EffectiveFeedbackScore;
import com.comet.opik.domain.EntityFeedbackScores;
import com.comet.opik.domain.FeedbackScoreDAO;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            case ANNOTATION_QUEUE_ADD -> Mono.just(Set.of());
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
}
