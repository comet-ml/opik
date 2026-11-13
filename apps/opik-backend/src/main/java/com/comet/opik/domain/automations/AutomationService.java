package com.comet.opik.domain.automations;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.Project;
import com.comet.opik.api.annotationqueue.Conditions;
import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.AutomationDryRun;
import com.comet.opik.api.automations.AutomationUpdate;
import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.domain.AnnotationQueueConditionEvaluator;
import com.comet.opik.domain.AnnotationQueueDAO;
import com.comet.opik.domain.DatasetDAO;
import com.comet.opik.domain.EffectiveFeedbackScore;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.evaluators.AutomationRuleAutomationDAO;
import com.comet.opik.domain.evaluators.AutomationRuleAutomationModel;
import com.comet.opik.domain.evaluators.AutomationRuleDAO;
import com.comet.opik.domain.evaluators.AutomationRuleProjectsDAO;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.cache.CacheEvict;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.automations.AutomationRefusal.CONDITION_UNKNOWN_OUTPUT;
import static com.comet.opik.domain.automations.AutomationRefusal.CONDITION_UNKNOWN_STEP;
import static com.comet.opik.domain.automations.AutomationRefusal.DUPLICATE_STEP_ID;
import static com.comet.opik.domain.automations.AutomationRefusal.STEP_SCOPE_MISMATCH;
import static com.comet.opik.domain.automations.AutomationRefusal.TARGET_NOT_FOUND;
import static com.comet.opik.domain.automations.AutomationRefusal.UNKNOWN_EVENT;
import static com.comet.opik.domain.automations.AutomationRefusal.UNKNOWN_STEP;
import static com.comet.opik.domain.automations.AutomationRefusal.refuse;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Automations as a resource: validation, name resolution, storage as an {@code automation} rule, the
 * dry-run estimate, and the guard the score listener asks.
 *
 * <p>Blocking JDBI behind a {@link TransactionTemplate} for the rule itself, as the queue router is; the
 * reactive pieces are the target lookups (queues live in ClickHouse) and the estimate.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AutomationService {

    public static final String CACHE_NAME = "automations";
    public static final int ESTIMATE_WINDOW_DAYS = 14;
    public static final int STATS_WINDOW_DAYS = 7;
    private static final int ESTIMATE_ROW_LIMIT = 50_000;
    private static final int LIST_ALL_LIMIT = 1_000;
    private static final float FULL_SAMPLING_RATE = 1.0f;
    private static final TypeReference<List<Automation.Trigger>> TRIGGERS = new TypeReference<>() {
    };
    private static final TypeReference<List<Automation.Step>> STEPS = new TypeReference<>() {
    };

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ProjectService projectService;
    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull FeedbackScoreDAO feedbackScoreDAO;
    private final @NonNull AnnotationQueueConditionEvaluator evaluator;
    private final @NonNull AutomationRunService runService;

    public Automation get(@NonNull String workspaceId, @NonNull UUID id) {
        var model = transactionTemplate.inTransaction(READ_ONLY, handle -> {
            try (var rows = handle.attach(AutomationRuleAutomationDAO.class).findByIds(workspaceId, List.of(id))) {
                return rows.findFirst();
            }
        }).orElseThrow(() -> new NotFoundException("Automation not found: '%s'".formatted(id)));

        return decorate(workspaceId, List.of(toApi(model))).getFirst();
    }

    public Automation.AutomationPage find(@NonNull String workspaceId, @Nullable UUID projectId,
            @Nullable Boolean enabled, @Nullable String recipe, @Nullable String event, int page, int size) {

        var found = transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleAutomationDAO.class);
            long total = dao.count(workspaceId, projectId, enabled, recipe, event);
            var models = dao.find(workspaceId, projectId, enabled, recipe, event, size, (page - 1) * size);
            return Map.entry(total, models);
        });

        var content = decorate(workspaceId, found.getValue().stream().map(this::toApi).toList());
        return Automation.AutomationPage.builder()
                .content(content).page(page).size(content.size()).total(found.getKey()).build();
    }

    /** Every automation of a project, undecorated: what the workflow and recommendation reads reason over. */
    public List<Automation> findByProject(@NonNull String workspaceId, @NonNull UUID projectId,
            @Nullable Boolean enabled) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> handle
                .attach(AutomationRuleAutomationDAO.class)
                .find(workspaceId, projectId, enabled, null, null, LIST_ALL_LIMIT, 0))
                .stream().map(this::toApi).toList();
    }

    /** Enabled automations listening to the event, paused ones included: the executor decides on those. */
    public List<Automation> findEnabledByProjects(@NonNull String workspaceId, Set<UUID> projectIds,
            @NonNull Automation.Event event) {
        if (CollectionUtils.isEmpty(projectIds)) {
            return List.of();
        }
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            try (var rows = handle.attach(AutomationRuleAutomationDAO.class)
                    .findEnabledByProjects(workspaceId, List.copyOf(projectIds), event.getValue())) {
                return rows.map(this::toApi).toList();
            }
        });
    }

    /**
     * The listener's guard, cached and evicted per workspace exactly as the queue routing guard is: a
     * null project is the batch score path, which widens the question to the workspace.
     */
    @Cacheable(name = CACHE_NAME, key = "$workspaceId + '-' + ($projectId == '' ? 'all' : $projectId) + '-' + $event", returnType = Boolean.class)
    public boolean hasEnabledAutomation(@NonNull String workspaceId, @Nullable UUID projectId,
            @NonNull String event) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleAutomationDAO.class);
            return projectId == null
                    ? dao.existsEnabledByWorkspace(workspaceId, event)
                    : dao.existsEnabledByProject(workspaceId, projectId, event);
        });
    }

    public Mono<AutomationDryRun> dryRun(@NonNull String workspaceId, @NonNull Automation definition) {
        return resolve(workspaceId, definition)
                .flatMap(resolved -> estimate(workspaceId, resolved.projectId(), resolved.triggers(),
                        resolved.limits())
                        .map(estimate -> AutomationDryRun.builder()
                                .automation(resolved).estimate(estimate).warnings(List.of()).build()));
    }

    public Mono<Automation> create(@NonNull String workspaceId, @NonNull String userName,
            @NonNull Automation definition) {
        return resolve(workspaceId, definition)
                .flatMap(resolved -> Mono.fromCallable(() -> save(workspaceId, userName, resolved))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<AutomationDryRun> dryRunUpdate(@NonNull String workspaceId, @NonNull UUID id,
            @NonNull AutomationUpdate update) {
        return dryRun(workspaceId, merge(get(workspaceId, id), update));
    }

    public Mono<Automation> update(@NonNull String workspaceId, @NonNull String userName, @NonNull UUID id,
            @NonNull AutomationUpdate update) {
        var merged = merge(get(workspaceId, id), update);
        return resolve(workspaceId, merged)
                .flatMap(resolved -> Mono.fromCallable(() -> save(workspaceId, userName, resolved))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @CacheEvict(name = CACHE_NAME, key = "$workspaceId + '-*'", keyUsesPatternMatching = true)
    public void delete(@NonNull String workspaceId, @NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        transactionTemplate.inTransaction(WRITE, handle -> {
            // Subtype first, then the junction, then the parent: the reverse of the write order.
            handle.attach(AutomationRuleAutomationDAO.class).deleteByIds(ids);
            handle.attach(AutomationRuleProjectsDAO.class).deleteByRuleIds(ids, workspaceId);
            handle.attach(AutomationRuleDAO.class).deleteBaseRules(ids, workspaceId,
                    AutomationRule.AutomationRuleAction.AUTOMATION.getAction());
            return null;
        });
        log.info("Deleted automations, count '{}', workspace '{}'", ids.size(), workspaceId);
    }

    /** A daily limit was hit: nothing runs until {@code until}, and reads say why. */
    public void pause(@NonNull UUID id, @NonNull Instant until, @NonNull String reason) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(AutomationRuleAutomationDAO.class).pause(id, until, reason);
            return null;
        });
        log.info("Paused automation '{}' until '{}', reason '{}'", id, until, reason);
    }

    /**
     * Validates the definition against the closed sets and resolves every target name to an id (and id
     * to a name), so what is stored names both. Refusals carry the code an agent maps.
     */
    public Mono<Automation> resolve(@NonNull String workspaceId, @NonNull Automation definition) {
        var triggers = definition.triggers().stream()
                .map(trigger -> trigger.toBuilder()
                        .scope(Optional.ofNullable(trigger.scope()).orElse(Automation.Scope.TRACE)).build())
                .toList();

        for (var trigger : triggers) {
            if (Automation.Event.fromString(trigger.event()) == null) {
                throw refuse(UNKNOWN_EVENT, "'%s' is not an event; known: %s".formatted(trigger.event(),
                        names(Automation.Event.values())));
            }
        }

        var seen = new HashSet<String>();
        var stepTypes = new ArrayList<Automation.StepType>();
        for (var step : definition.steps()) {
            if (!seen.add(step.id())) {
                throw refuse(DUPLICATE_STEP_ID, "step id '%s' is used twice".formatted(step.id()));
            }
            var type = Automation.StepType.fromString(step.type());
            if (type == null) {
                throw refuse(UNKNOWN_STEP, "'%s' is not a step; known: %s".formatted(step.type(),
                        names(Automation.StepType.values())));
            }
            stepTypes.add(type);
            validateOnlyIf(step, definition.steps());
        }

        var scopes = triggers.stream().map(Automation.Trigger::scope).collect(Collectors.toSet());

        return Flux.fromIterable(definition.steps())
                .concatMap(step -> resolveStep(workspaceId, definition.projectId(), step, scopes))
                .collectList()
                .map(steps -> definition.toBuilder()
                        .triggers(triggers)
                        .steps(steps)
                        .enabled(Optional.ofNullable(definition.enabled()).orElse(true))
                        .createdVia(Optional.ofNullable(definition.createdVia()).orElse(Automation.CreatedVia.API))
                        .build());
    }

    /**
     * How the triggers would have behaved over the last {@value #ESTIMATE_WINDOW_DAYS} days, replayed
     * against the project's effective scores. A trigger without conditions counts every scored entity.
     */
    public Mono<AutomationDryRun.Estimate> estimate(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull List<Automation.Trigger> triggers, @Nullable Automation.Limits limits) {

        Instant since = Instant.now().minus(Duration.ofDays(ESTIMATE_WINDOW_DAYS));
        var scoreTriggers = triggers.stream()
                .filter(trigger -> Automation.Event
                        .fromString(trigger.event()) == Automation.Event.FEEDBACK_SCORE_CREATED)
                .toList();

        return Flux.fromIterable(scoreTriggers)
                .flatMap(trigger -> scoreNames(trigger, projectId)
                        .flatMapMany(names -> feedbackScoreDAO.getEffectiveScoresByProject(
                                entityTypeOf(trigger.scope()), projectId, names, since, ESTIMATE_ROW_LIMIT))
                        .collect(Collectors.groupingBy(EffectiveFeedbackScore::entityId,
                                Collectors.toMap(EffectiveFeedbackScore::name, EffectiveFeedbackScore::value,
                                        (first, ignored) -> first)))
                        .map(byEntity -> {
                            long matched = byEntity.values().stream()
                                    .filter(scores -> matches(trigger, scores))
                                    .count();
                            long items = trigger.perDay() == null
                                    ? matched
                                    : Math.min(matched, (long) trigger.perDay() * ESTIMATE_WINDOW_DAYS);
                            return new long[]{byEntity.size(), matched, items};
                        }))
                .reduce(new long[]{0, 0, 0}, (acc, next) -> new long[]{acc[0] + next[0], acc[1] + next[1],
                        acc[2] + next[2]})
                .map(sums -> {
                    long items = sums[2];
                    if (limits != null && limits.maxItemsPerDay() != null) {
                        items = Math.min(items, (long) limits.maxItemsPerDay() * ESTIMATE_WINDOW_DAYS);
                    }
                    if (limits != null && limits.maxRunsPerDay() != null) {
                        items = Math.min(items, (long) limits.maxRunsPerDay() * ESTIMATE_WINDOW_DAYS);
                    }
                    return AutomationDryRun.Estimate.builder()
                            .windowDays(ESTIMATE_WINDOW_DAYS)
                            .fired(sums[0]).matched(sums[1]).items(items)
                            .costUsd(BigDecimal.ZERO)
                            .build();
                })
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }

    /** Whether an entity's effective scores fire the trigger. No conditions means any score does. */
    public boolean matches(@NonNull Automation.Trigger trigger, @NonNull Map<String, BigDecimal> scores) {
        Conditions conditions = trigger.conditions();
        if (conditions == null || CollectionUtils.isEmpty(conditions.groups())) {
            return !scores.isEmpty();
        }
        return evaluator.matches(conditions, scores);
    }

    public static EntityType entityTypeOf(@Nullable Automation.Scope scope) {
        return scope == Automation.Scope.THREAD ? EntityType.THREAD : EntityType.TRACE;
    }

    private Mono<Set<String>> scoreNames(Automation.Trigger trigger, UUID projectId) {
        var names = Optional.ofNullable(trigger.conditions())
                .map(Conditions::groups).orElse(List.of()).stream()
                .flatMap(group -> group.conditions().stream())
                .map(condition -> condition.scoreName())
                .collect(Collectors.toSet());
        if (!names.isEmpty()) {
            return Mono.just(names);
        }
        return feedbackScoreDAO.getTraceFeedbackScoreNames(projectId).map(Set::copyOf);
    }

    private static void validateOnlyIf(Automation.Step step, List<Automation.Step> steps) {
        if (step.onlyIf() == null) {
            return;
        }
        // No step declares an output yet, so every reference is to an unknown step or an unknown output;
        // the two codes still tell the caller which half of "<step>.<output>" is wrong.
        var earlier = steps.stream().takeWhile(other -> !other.id().equals(step.id()))
                .map(Automation.Step::id).collect(Collectors.toSet());
        for (var group : step.onlyIf().groups()) {
            for (var condition : group.conditions()) {
                String reference = condition.stepOutput();
                int dot = reference.indexOf('.');
                String stepId = dot > 0 ? reference.substring(0, dot) : reference;
                if (!earlier.contains(stepId)) {
                    throw refuse(CONDITION_UNKNOWN_STEP,
                            "only_if on step '%s' names '%s', which is not an earlier step".formatted(step.id(),
                                    stepId));
                }
                throw refuse(CONDITION_UNKNOWN_OUTPUT,
                        "only_if on step '%s' names '%s', but step '%s' has no outputs".formatted(step.id(),
                                reference, stepId));
            }
        }
    }

    private Mono<Automation.Step> resolveStep(String workspaceId, UUID projectId, Automation.Step step,
            Set<Automation.Scope> scopes) {
        return switch (Objects.requireNonNull(Automation.StepType.fromString(step.type()))) {
            case ANNOTATION_QUEUE_ADD -> resolveQueue(workspaceId, projectId, step, scopes);
            case TEST_SUITE_ADD -> Mono.fromCallable(() -> resolveDataset(workspaceId, projectId, step,
                    DatasetType.TEST_SUITE, scopes)).subscribeOn(Schedulers.boundedElastic());
            case DATASET_ADD -> Mono.fromCallable(() -> resolveDataset(workspaceId, projectId, step,
                    DatasetType.DATASET, scopes)).subscribeOn(Schedulers.boundedElastic());
        };
    }

    private Mono<Automation.Step> resolveQueue(String workspaceId, UUID projectId, Automation.Step step,
            Set<Automation.Scope> scopes) {
        Mono<AnnotationQueue> queue;
        if (step.annotationQueueId() != null) {
            queue = annotationQueueDAO.findById(step.annotationQueueId())
                    .filter(found -> projectId.equals(found.projectId()));
        } else if (step.annotationQueueName() != null) {
            queue = annotationQueueDAO
                    .find(1, 100,
                            AnnotationQueueSearchCriteria.builder().name(step.annotationQueueName()).filters(List.of())
                                    .sortingFields(List.of()).build())
                    .flatMapIterable(AnnotationQueue.AnnotationQueuePage::content)
                    .filter(found -> projectId.equals(found.projectId())
                            && step.annotationQueueName().equals(found.name()))
                    .next();
        } else {
            return Mono.error(refuse(TARGET_NOT_FOUND,
                    "step '%s' needs annotation_queue_id or annotation_queue_name".formatted(step.id())));
        }

        return queue
                .switchIfEmpty(Mono.error(() -> refuse(TARGET_NOT_FOUND,
                        "annotation queue '%s' not found in the project".formatted(
                                Optional.ofNullable(step.annotationQueueName())
                                        .orElseGet(() -> String.valueOf(step.annotationQueueId()))))))
                .map(found -> {
                    var queueScope = Automation.Scope.fromString(found.scope().getValue());
                    if (!scopes.contains(queueScope) || scopes.size() != 1) {
                        throw refuse(STEP_SCOPE_MISMATCH,
                                "annotation queue '%s' takes %ss, but the trigger scope is %s".formatted(found.name(),
                                        queueScope.getValue(), scopes.stream().map(Automation.Scope::getValue)
                                                .sorted().collect(Collectors.joining(", "))));
                    }
                    return step.toBuilder().annotationQueueId(found.id()).annotationQueueName(found.name()).build();
                })
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }

    private Automation.Step resolveDataset(String workspaceId, UUID projectId, Automation.Step step,
            DatasetType type, Set<Automation.Scope> scopes) {
        if (scopes.contains(Automation.Scope.THREAD)) {
            throw refuse(STEP_SCOPE_MISMATCH,
                    "step '%s' (%s) adds traces, but the trigger scope is thread".formatted(step.id(), step.type()));
        }
        boolean suite = type == DatasetType.TEST_SUITE;
        UUID id = suite ? step.testSuiteId() : step.datasetId();
        String name = suite ? step.testSuiteName() : step.datasetName();
        String noun = suite ? "test suite" : "dataset";
        if (id == null && name == null) {
            throw refuse(TARGET_NOT_FOUND, "step '%s' needs a %s id or name".formatted(step.id(), noun));
        }

        var dataset = transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            return id != null
                    ? dao.findById(id, workspaceId)
                    : dao.findByName(workspaceId, name, projectId).or(() -> dao.findByName(workspaceId, name, null));
        })
                .filter(found -> typeOf(found) == type)
                .orElseThrow(() -> refuse(TARGET_NOT_FOUND, "%s '%s' not found".formatted(noun,
                        name != null ? name : id)));

        return suite
                ? step.toBuilder().testSuiteId(dataset.id()).testSuiteName(dataset.name()).build()
                : step.toBuilder().datasetId(dataset.id()).datasetName(dataset.name()).build();
    }

    private static DatasetType typeOf(Dataset dataset) {
        return dataset.type() == null ? DatasetType.DATASET : dataset.type();
    }

    private Automation save(String workspaceId, String userName, Automation resolved) {
        projectService.validateProjectIdExists(resolved.projectId(), workspaceId);
        UUID id = resolved.id() == null ? idGenerator.generateId() : resolved.id();
        boolean existing = resolved.id() != null;

        var model = AutomationRuleAutomationModel.builder()
                .id(id)
                .projectId(resolved.projectId())
                .name(resolved.name())
                .samplingRate(FULL_SAMPLING_RATE)
                .enabled(Boolean.TRUE.equals(resolved.enabled()))
                .triggerScope(EvalTriggerScope.PRODUCTION)
                .build();

        transactionTemplate.inTransaction(WRITE, handle -> {
            var ruleDao = handle.attach(AutomationRuleDAO.class);
            if (existing) {
                ruleDao.updateBaseRule(id, workspaceId, resolved.name(), FULL_SAMPLING_RATE,
                        model.enabled(), EvalTriggerScope.PRODUCTION, null);
            } else {
                ruleDao.saveBaseRule(model, workspaceId);
                handle.attach(AutomationRuleProjectsDAO.class).saveRuleProjects(id, Set.of(resolved.projectId()),
                        workspaceId);
            }
            handle.attach(AutomationRuleAutomationDAO.class).save(id,
                    JsonUtils.writeValueAsString(resolved.triggers()),
                    JsonUtils.writeValueAsString(resolved.steps()),
                    resolved.limits() == null ? null : JsonUtils.writeValueAsString(resolved.limits()),
                    resolved.recipe() == null ? null : resolved.recipe().id(),
                    resolved.recipe() == null ? null : resolved.recipe().version(),
                    resolved.createdVia().getValue(),
                    userName);
            return null;
        });
        evict(workspaceId);
        log.info("Saved automation '{}' ('{}'), project '{}', workspace '{}'", id, resolved.name(),
                resolved.projectId(), workspaceId);
        return get(workspaceId, id);
    }

    @CacheEvict(name = CACHE_NAME, key = "$workspaceId + '-*'", keyUsesPatternMatching = true)
    public void evict(@NonNull String workspaceId) {
        // The annotation does the work; a write evicts the whole workspace because one change can flip
        // the workspace-wide key and every project key at once.
    }

    private static Automation merge(Automation current, AutomationUpdate update) {
        return current.toBuilder()
                .name(Optional.ofNullable(update.name()).orElse(current.name()))
                .enabled(Optional.ofNullable(update.enabled()).orElse(current.enabled()))
                .triggers(Optional.ofNullable(update.triggers()).orElse(current.triggers()))
                .steps(Optional.ofNullable(update.steps()).orElse(current.steps()))
                .limits(Optional.ofNullable(update.limits()).orElse(current.limits()))
                .stats(null)
                .build();
    }

    private Automation toApi(AutomationRuleAutomationModel model) {
        Instant now = Instant.now();
        var paused = model.pausedUntil() != null && model.pausedUntil().isAfter(now)
                ? Automation.PausedReason.builder().limit(model.pausedReason()).until(model.pausedUntil()).build()
                : null;
        return Automation.builder()
                .id(model.id())
                .projectId(model.projectId())
                .name(model.name())
                .action(Automation.ACTION)
                .enabled(model.enabled())
                .recipe(model.recipeId() == null
                        ? null
                        : Automation.Recipe.builder().id(model.recipeId()).version(model.recipeVersion()).build())
                .triggers(JsonUtils.readValue(model.triggers(), TRIGGERS))
                .steps(JsonUtils.readValue(model.steps(), STEPS))
                .limits(model.limits() == null ? null : JsonUtils.readValue(model.limits(), Automation.Limits.class))
                .createdVia(Automation.CreatedVia.fromString(model.createdVia()))
                .pausedReason(paused)
                .createdAt(model.createdAt())
                .createdBy(model.createdBy())
                .lastUpdatedAt(model.lastUpdatedAt())
                .lastUpdatedBy(model.lastUpdatedBy())
                .build();
    }

    /** Project names and the 7-day stats, for what the API returns. */
    private List<Automation> decorate(String workspaceId, List<Automation> automations) {
        if (automations.isEmpty()) {
            return automations;
        }
        Map<UUID, String> projectNames = projectService
                .findByIds(workspaceId, automations.stream().map(Automation::projectId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Project::id, Project::name));
        Map<UUID, Automation.Stats> stats = runService.stats(workspaceId,
                automations.stream().map(Automation::id).toList(),
                Instant.now().minus(Duration.ofDays(STATS_WINDOW_DAYS)), STATS_WINDOW_DAYS);
        return automations.stream()
                .map(automation -> automation.toBuilder()
                        .projectName(projectNames.get(automation.projectId()))
                        .stats(stats.getOrDefault(automation.id(), AutomationRunService.emptyStats(STATS_WINDOW_DAYS)))
                        .build())
                .toList();
    }

    private static <E extends Enum<E>> String names(E[] values) {
        return java.util.Arrays.stream(values).map(value -> switch (value) {
            case Automation.Event event -> event.getValue();
            case Automation.StepType type -> type.getValue();
            default -> value.name();
        }).collect(Collectors.joining(", "));
    }

    /** Groups one entity's rows into the name-to-value view the conditions are evaluated against. */
    public static Map<UUID, Map<String, BigDecimal>> groupByEntity(List<EffectiveFeedbackScore> rows) {
        return rows.stream().collect(Collectors.groupingBy(EffectiveFeedbackScore::entityId,
                Collectors.toMap(EffectiveFeedbackScore::name, EffectiveFeedbackScore::value,
                        (first, ignored) -> first)));
    }
}
