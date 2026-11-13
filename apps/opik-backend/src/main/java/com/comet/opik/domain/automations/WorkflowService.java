package com.comet.opik.domain.automations;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.Project;
import com.comet.opik.api.annotationqueue.ConditionGroup;
import com.comet.opik.api.annotationqueue.Conditions;
import com.comet.opik.api.annotationqueue.ScoreCondition;
import com.comet.opik.api.annotationqueue.ScoreConditionOperator;
import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.AutomationDryRun;
import com.comet.opik.api.automations.AutomationUpdate;
import com.comet.opik.api.automations.Workflow;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.DatasetDAO;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.automations.AutomationRefusal.UNKNOWN_RECIPE;
import static com.comet.opik.domain.automations.AutomationRefusal.refuse;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * The recipes: a workflow is one or two automations that do one job on a cadence, stamped with the recipe
 * id so reads can tell which workflows are on. Nothing is stored for the workflow itself.
 *
 * <p>Two recipes exist so far. {@code collect} keeps the production traces a judge scored low in the
 * regression suite; {@code align} sends a random few a day to a review queue, the half of judge alignment
 * that needs no new event producer. The rest arrive with their events.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class WorkflowService {

    public static final String COLLECT = "collect";
    public static final String ALIGN = "align";
    public static final int VERSION = 1;
    public static final String DEFAULT_SCORE_NAME = "resolution";
    public static final double DEFAULT_THRESHOLD = 0.5;
    public static final int DEFAULT_PER_DAY = 10;
    public static final int DEFAULT_MAX_ITEMS_PER_DAY = 200;
    public static final String REVIEW_QUEUE_NAME = "Judge review";
    private static final List<String> RECIPES = List.of(COLLECT, ALIGN);

    private final @NonNull AutomationService automationService;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull AnnotationQueueService annotationQueueService;
    private final @NonNull FeedbackScoreDAO feedbackScoreDAO;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Builder(toBuilder = true)
    record Recipe(String id, String name, String description, Workflow.Overrides overrides) {
    }

    public Mono<List<Workflow>> list(@NonNull String workspaceId, @NonNull UUID projectId) {
        Project project = projectService.get(projectId, workspaceId);
        List<Automation> existing = automationService.findByProject(workspaceId, projectId, null);

        return feedbackScoreDAO.getTraceFeedbackScoreNames(projectId)
                .flatMap(scoreNames -> Flux.fromIterable(RECIPES)
                        .concatMap(recipe -> describe(workspaceId, project, recipe, existing, scoreNames))
                        .collectList());
    }

    public Mono<Workflow.Enabled> enable(@NonNull String workspaceId, @NonNull String userName,
            @NonNull Workflow.EnableRequest request, boolean dryRun) {
        Project project = projectService.get(request.projectId(), workspaceId);
        List<Automation> existing = automationService.findByProject(workspaceId, project.id(), null);
        return feedbackScoreDAO.getTraceFeedbackScoreNames(project.id())
                .flatMap(scoreNames -> enable(workspaceId, userName, request, dryRun, project, existing,
                        scoreNames));
    }

    private Mono<Workflow.Enabled> enable(String workspaceId, String userName, Workflow.EnableRequest request,
            boolean dryRun, Project project, List<Automation> existing, List<String> scoreNames) {
        var recipe = recipe(request.recipe(), project, request.overrides(), scoreNames);
        var templates = templates(recipe, project)
                .stream()
                .map(template -> template.toBuilder()
                        .createdVia(Optional.ofNullable(request.createdVia()).orElse(Automation.CreatedVia.API))
                        .build())
                .toList();

        // Already on: enabling again is idempotent and returns what runs, so an agent asking twice does
        // not get two copies of the automation.
        var alreadyOn = templates.stream()
                .map(template -> byRecipe(existing, recipe.id(), template))
                .flatMap(Optional::stream)
                .toList();
        var toCreate = templates.stream()
                .filter(template -> byRecipe(existing, recipe.id(), template).isEmpty())
                .toList();

        Mono<List<Automation>> created = dryRun
                ? Mono.just(List.of())
                : ensureTargets(workspaceId, userName, project, toCreate)
                        .thenMany(Flux.fromIterable(toCreate))
                        .concatMap(template -> automationService.create(workspaceId, userName, template))
                        .collectList();

        return created
                .flatMap(automations -> Flux.fromIterable(alreadyOn)
                        .filter(automation -> !Boolean.TRUE.equals(automation.enabled()))
                        .concatMap(automation -> dryRun
                                ? Mono.just(automation)
                                : automationService.update(workspaceId, userName, automation.id(),
                                        AutomationUpdate.builder().enabled(true).build()))
                        .collectList()
                        .map(reenabled -> automations))
                .flatMap(automations -> estimate(workspaceId, project.id(), templates)
                        .map(estimate -> Workflow.Enabled.builder()
                                .created(automations)
                                .wouldCreate(toCreate.stream().map(Automation::name).toList())
                                .estimate(estimate)
                                .warnings(List.of())
                                .build()));
    }

    public Workflow.Disabled disable(@NonNull String workspaceId, @NonNull String userName,
            @NonNull Workflow.DisableRequest request) {
        if (!RECIPES.contains(request.recipe())) {
            throw refuse(UNKNOWN_RECIPE, "'%s' is not a recipe; known: %s".formatted(request.recipe(), RECIPES));
        }
        var disabled = automationService.findByProject(workspaceId, request.projectId(), true).stream()
                .filter(automation -> automation.recipe() != null && request.recipe().equals(automation.recipe().id()))
                .map(automation -> automationService.update(workspaceId, userName, automation.id(),
                        AutomationUpdate.builder().enabled(false).build()).block())
                .map(Automation::id)
                .toList();
        return Workflow.Disabled.builder().disabled(disabled).build();
    }

    private Mono<Workflow> describe(String workspaceId, Project project, String recipeId,
            List<Automation> existing, List<String> scoreNames) {
        var recipe = recipe(recipeId, project, null, scoreNames);
        var templates = templates(recipe, project);
        var matched = templates.stream()
                .map(template -> byRecipe(existing, recipe.id(), template).orElse(template))
                .toList();
        long on = matched.stream().filter(automation -> automation.id() != null
                && Boolean.TRUE.equals(automation.enabled())).count();
        var state = on == 0 ? Workflow.State.OFF : on == matched.size() ? Workflow.State.ON : Workflow.State.PARTLY_ON;
        // What runs, when something does: the overrides it was enabled with, not the recipe's defaults.
        var overrides = matched.stream()
                .filter(automation -> automation.id() != null)
                .findFirst()
                .map(automation -> overridesOf(automation, recipe.overrides()))
                .orElse(recipe.overrides());

        return estimate(workspaceId, project.id(), templates)
                .map(estimate -> Workflow.builder()
                        .recipe(recipe.id()).version(VERSION).name(recipe.name()).description(recipe.description())
                        .state(state).prerequisitesMet(true).blockedReason(null)
                        .automations(matched)
                        .estimate(estimate)
                        .overrides(overrides)
                        .availableScoreNames(scoreNames)
                        .build());
    }

    /** The overrides an existing automation embodies, read back from its trigger, step and limits. */
    private static Workflow.Overrides overridesOf(Automation automation, Workflow.Overrides defaults) {
        var trigger = automation.triggers().getFirst();
        var condition = Optional.ofNullable(trigger.conditions())
                .map(Conditions::groups).filter(groups -> !groups.isEmpty())
                .map(List::getFirst).map(ConditionGroup::conditions).filter(conditions -> !conditions.isEmpty())
                .map(List::getFirst);
        var step = automation.steps().getFirst();
        return Workflow.Overrides.builder()
                .scoreName(condition.map(ScoreCondition::scoreName).orElse(defaults.scoreName()))
                .threshold(defaults.threshold() == null
                        ? null
                        : condition.map(ScoreCondition::value).orElse(defaults.threshold()))
                .testSuiteName(step.testSuiteName() != null ? step.testSuiteName() : defaults.testSuiteName())
                .annotationQueueName(step.annotationQueueName() != null
                        ? step.annotationQueueName()
                        : defaults.annotationQueueName())
                .perDay(trigger.perDay() != null ? trigger.perDay() : defaults.perDay())
                .maxItemsPerDay(defaults.maxItemsPerDay() == null
                        ? null
                        : Optional.ofNullable(automation.limits()).map(Automation.Limits::maxItemsPerDay)
                                .orElse(defaults.maxItemsPerDay()))
                .build();
    }

    private Mono<AutomationDryRun.Estimate> estimate(String workspaceId, UUID projectId, List<Automation> templates) {
        return Flux.fromIterable(templates)
                .concatMap(template -> automationService.estimate(workspaceId, projectId, template.triggers(),
                        template.limits()))
                .reduce((left, right) -> AutomationDryRun.Estimate.builder()
                        .windowDays(left.windowDays())
                        .fired(left.fired() + right.fired()).matched(left.matched() + right.matched())
                        .items(left.items() + right.items()).costUsd(left.costUsd().add(right.costUsd()))
                        .build())
                .defaultIfEmpty(AutomationDryRun.Estimate.builder()
                        .windowDays(AutomationService.ESTIMATE_WINDOW_DAYS).costUsd(java.math.BigDecimal.ZERO).build());
    }

    /**
     * The automation a recipe created for this job, whatever it was named: the recipe id and the step it
     * writes with identify it, since a name carries the score it was enabled on.
     */
    private static Optional<Automation> byRecipe(List<Automation> existing, String recipeId, Automation template) {
        String stepType = template.steps().getFirst().type();
        return existing.stream()
                .filter(automation -> automation.recipe() != null && recipeId.equals(automation.recipe().id()))
                .filter(automation -> automation.steps().stream().anyMatch(step -> stepType.equals(step.type())))
                .findFirst();
    }

    Recipe recipe(String id, Project project, Workflow.Overrides overrides, List<String> scoreNames) {
        var given = Optional.ofNullable(overrides).orElse(Workflow.Overrides.builder().build());
        // The judge the recipe defaults to: `resolution` when the project has it, else the first score
        // the project does write, so an apply call taken as offered fires on something real.
        String defaultScoreName = scoreNames.contains(DEFAULT_SCORE_NAME) || scoreNames.isEmpty()
                ? DEFAULT_SCORE_NAME
                : scoreNames.getFirst();
        return switch (id) {
            case COLLECT -> new Recipe(COLLECT, "Collect",
                    "A judge score lands on a production trace: keep the trace in the regression suite when the "
                            + "score is below the threshold.",
                    Workflow.Overrides.builder()
                            .scoreName(Optional.ofNullable(given.scoreName()).orElse(defaultScoreName))
                            .threshold(Optional.ofNullable(given.threshold()).orElse(DEFAULT_THRESHOLD))
                            .testSuiteName(Optional.ofNullable(given.testSuiteName()).orElse(suiteName(project)))
                            .maxItemsPerDay(
                                    Optional.ofNullable(given.maxItemsPerDay()).orElse(DEFAULT_MAX_ITEMS_PER_DAY))
                            .build());
            case ALIGN -> new Recipe(ALIGN, "Align",
                    "A judge score lands on a production trace: send a few a day, at random, to a review queue "
                            + "so people can check the judge.",
                    Workflow.Overrides.builder()
                            .scoreName(Optional.ofNullable(given.scoreName()).orElse(defaultScoreName))
                            .annotationQueueName(
                                    Optional.ofNullable(given.annotationQueueName()).orElse(REVIEW_QUEUE_NAME))
                            .perDay(Optional.ofNullable(given.perDay()).orElse(DEFAULT_PER_DAY))
                            .build());
            default -> throw refuse(UNKNOWN_RECIPE, "'%s' is not a recipe; known: %s".formatted(id, RECIPES));
        };
    }

    /** The automations a recipe creates for a project, as definitions the service resolves and stores. */
    List<Automation> templates(Recipe recipe, Project project) {
        var overrides = recipe.overrides();
        return switch (recipe.id()) {
            case COLLECT -> List.of(Automation.builder()
                    .projectId(project.id()).name("Low %s traces to the suite".formatted(overrides.scoreName()))
                    .enabled(true)
                    .recipe(Automation.Recipe.builder().id(COLLECT).version(VERSION).build())
                    .triggers(List.of(Automation.Trigger.builder()
                            .event(Automation.Event.FEEDBACK_SCORE_CREATED.getValue()).scope(Automation.Scope.TRACE)
                            .conditions(below(overrides.scoreName(), overrides.threshold()))
                            .build()))
                    .steps(List.of(Automation.Step.builder()
                            .id("keep").type(Automation.StepType.TEST_SUITE_ADD.getValue())
                            .testSuiteName(overrides.testSuiteName())
                            .build()))
                    .limits(Automation.Limits.builder().maxItemsPerDay(overrides.maxItemsPerDay()).build())
                    .build());
            case ALIGN -> List.of(Automation.builder()
                    .projectId(project.id()).name("Random %s traces to review".formatted(overrides.scoreName()))
                    .enabled(true)
                    .recipe(Automation.Recipe.builder().id(ALIGN).version(VERSION).build())
                    .triggers(List.of(Automation.Trigger.builder()
                            .event(Automation.Event.FEEDBACK_SCORE_CREATED.getValue()).scope(Automation.Scope.TRACE)
                            // Any value of the judge's score: the sample has to be blind to the verdict.
                            .conditions(any(overrides.scoreName()))
                            .perDay(overrides.perDay())
                            .build()))
                    .steps(List.of(Automation.Step.builder()
                            .id("review").type(Automation.StepType.ANNOTATION_QUEUE_ADD.getValue())
                            .annotationQueueName(overrides.annotationQueueName())
                            .build()))
                    .limits(Automation.Limits.builder().maxItemsPerDay(overrides.perDay()).build())
                    .build());
            default -> throw refuse(UNKNOWN_RECIPE, "'%s' is not a recipe".formatted(recipe.id()));
        };
    }

    /** Creates the suite or queue a template names when the project has none yet. */
    private Mono<Void> ensureTargets(String workspaceId, String userName, Project project,
            List<Automation> templates) {
        return Flux.fromIterable(templates)
                .flatMapIterable(Automation::steps)
                .concatMap(step -> switch (Automation.StepType.fromString(step.type())) {
                    case TEST_SUITE_ADD -> Mono.fromRunnable(() -> ensureDataset(workspaceId, userName, project,
                            step.testSuiteName(), DatasetType.TEST_SUITE)).subscribeOn(Schedulers.boundedElastic());
                    case DATASET_ADD -> Mono.fromRunnable(() -> ensureDataset(workspaceId, userName, project,
                            step.datasetName(), DatasetType.DATASET)).subscribeOn(Schedulers.boundedElastic());
                    case ANNOTATION_QUEUE_ADD -> ensureQueue(project, step.annotationQueueName());
                    case null -> Mono.empty();
                })
                .then();
    }

    /**
     * Written through the DAO rather than {@code DatasetService.save}, which reads the request-scoped
     * context and so cannot run on the worker thread this is called from.
     */
    private void ensureDataset(String workspaceId, String userName, Project project, String name, DatasetType type) {
        if (name == null) {
            return;
        }
        transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            boolean exists = dao.findByName(workspaceId, name, project.id())
                    .or(() -> dao.findByName(workspaceId, name, null))
                    .isPresent();
            if (!exists) {
                dao.save(Dataset.builder()
                        .id(idGenerator.generateId()).name(name).projectId(project.id()).type(type)
                        .createdBy(userName).lastUpdatedBy(userName)
                        .build(), workspaceId);
                log.info("Created {} '{}' for workflow, project '{}'", type.getValue(), name, project.id());
            }
            return null;
        });
    }

    private Mono<Void> ensureQueue(Project project, String name) {
        if (name == null) {
            return Mono.empty();
        }
        return annotationQueueService
                .find(1, 100,
                        AnnotationQueueSearchCriteria.builder().name(name).filters(List.of()).sortingFields(List.of())
                                .build())
                .flatMapIterable(AnnotationQueue.AnnotationQueuePage::content)
                .filter(queue -> project.id().equals(queue.projectId()) && name.equals(queue.name()))
                .hasElements()
                .flatMap(exists -> exists
                        ? Mono.empty()
                        : annotationQueueService.create(AnnotationQueue.builder()
                                .projectId(project.id()).name(name).scope(AnnotationQueue.AnnotationScope.TRACE)
                                .instructions("Does the judge's score match your own verdict?")
                                .build())
                                .doOnNext(id -> log.info("Created annotation queue '{}' for workflow, project '{}'",
                                        name, project.id()))
                                .then());
    }

    static String suiteName(Project project) {
        return "Regression · %s".formatted(project.name());
    }

    private static Conditions below(String scoreName, double threshold) {
        return Conditions.builder().groups(List.of(ConditionGroup.builder().conditions(List.of(
                ScoreCondition.builder().scoreName(scoreName).operator(ScoreConditionOperator.LESS_THAN)
                        .value(threshold).build()))
                .build())).build();
    }

    /** Matches the score whatever its value: the judges this is for score in [0, 1]. */
    private static Conditions any(String scoreName) {
        return Conditions.builder().groups(List.of(ConditionGroup.builder().conditions(List.of(
                ScoreCondition.builder().scoreName(scoreName).operator(ScoreConditionOperator.GREATER_THAN_OR_EQUAL)
                        .value(0.0).build()))
                .build())).build();
    }
}
