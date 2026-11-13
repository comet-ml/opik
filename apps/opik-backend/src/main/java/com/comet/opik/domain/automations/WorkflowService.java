package com.comet.opik.domain.automations;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.Project;
import com.comet.opik.api.Visibility;
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
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.automations.AutomationRefusal.MODEL_REQUIRED;
import static com.comet.opik.domain.automations.AutomationRefusal.PROMPT_NAME_REQUIRED;
import static com.comet.opik.domain.automations.AutomationRefusal.SCORE_NAME_REQUIRED;
import static com.comet.opik.domain.automations.AutomationRefusal.UNKNOWN_RECIPE;
import static com.comet.opik.domain.automations.AutomationRefusal.refuse;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * The recipes: a workflow is one or two automations that do one job on a cadence, stamped with the recipe
 * id so reads can tell which workflows are on. Nothing is stored for the workflow itself.
 *
 * <p>{@code collect} keeps the production traces a judge scored low in the regression suite; {@code gate}
 * runs the suite on every new prompt version or CI run; {@code improve} optimizes the prompt against the
 * suite every time it grows by N cases and saves a winner that gains enough; {@code align} sends a random
 * few scored traces a day to a review queue and, every N reviews, measures how often the judge and the
 * reviewers agree.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class WorkflowService {

    public static final String COLLECT = "collect";
    public static final String ALIGN = "align";
    public static final String GATE = "gate";
    public static final String IMPROVE = "improve";
    public static final int VERSION = 1;
    /** Stands in for the judge in a recipe shown before one was chosen; an enable never stores it. */
    public static final String SCORE_NAME_PLACEHOLDER = "<score_name>";
    public static final double DEFAULT_THRESHOLD = 0.5;
    public static final int DEFAULT_PER_DAY = 10;
    public static final int DEFAULT_MAX_ITEMS_PER_DAY = 200;
    public static final String REVIEW_QUEUE_NAME = "Judge review";
    public static final int DEFAULT_SUITE_GROWTH = 50;
    public static final int DEFAULT_REVIEWS = 20;
    public static final double DEFAULT_MIN_GAIN = 0.05;
    public static final double DEFAULT_MIN_AGREEMENT = 0.8;
    public static final String DEFAULT_METRIC = "levenshtein_ratio";
    public static final String DEFAULT_OPTIMIZER = "metaprompt";
    private static final List<String> RECIPES = List.of(COLLECT, GATE, ALIGN, IMPROVE);
    static final String GITHUB_ACTION = """
            # Opik Gate: tell Opik this commit ran, so the regression suite runs and a check comes back.
            - name: Opik gate
              env:
                OPIK_API_KEY: ${{ secrets.OPIK_API_KEY }}
                OPIK_WORKSPACE: %s
                OPIK_URL: %s
              run: |
                curl -sS -X POST "$OPIK_URL/v1/private/automations/events/ci" \\
                  -H "Authorization: $OPIK_API_KEY" -H "Comet-Workspace: $OPIK_WORKSPACE" \\
                  -H "Content-Type: application/json" \\
                  -d "{\\"project_id\\":\\"%s\\",\\"repository\\":\\"${{ github.repository }}\\",\\"branch\\":\\"${{ github.ref_name }}\\",\\"commit\\":\\"${{ github.sha }}\\",\\"run_url\\":\\"${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}\\"}"
            """;

    private final @NonNull AutomationService automationService;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull AnnotationQueueService annotationQueueService;
    private final @NonNull FeedbackScoreDAO feedbackScoreDAO;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull DatasetItemService datasetItemService;

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
        var recipe = recipe(request.recipe(), project, request.overrides());
        if (GATE.equals(recipe.id()) || IMPROVE.equals(recipe.id())) {
            if (recipe.overrides().promptName() == null) {
                throw refuse(PROMPT_NAME_REQUIRED, "name the prompt in overrides.prompt_name");
            }
            if (recipe.overrides().model() == null) {
                throw refuse(MODEL_REQUIRED,
                        "name the model the suite runs with in overrides.model (for example gpt-4o-mini)");
            }
        } else if (recipe.overrides().scoreName() == null) {
            // The judge is the user's call, never the recipe's: an agent has to ask which score to act on.
            throw refuse(SCORE_NAME_REQUIRED, scoreNames.isEmpty()
                    ? "name the judge score in overrides.score_name; this project writes no scores yet, so take a judge live first"
                    : "name the judge score in overrides.score_name; this project writes: %s".formatted(
                            String.join(", ", scoreNames)));
        }
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
                                .setup(GATE.equals(recipe.id())
                                        ? Workflow.Setup.builder().githubAction(githubAction(workspaceId, project))
                                                .build()
                                        : null)
                                .warnings(List.of())
                                .build()));
    }

    /** The step a GitHub workflow adds so CI runs fire Gate. The URL and workspace are the caller's to fill. */
    static String githubAction(String workspaceId, Project project) {
        return GITHUB_ACTION.formatted("<your workspace>", "<your Opik URL, e.g. https://www.comet.com/opik/api>",
                project.id());
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
        var recipe = recipe(recipeId, project, null);
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

        // Before a judge is chosen there is nothing to replay, so the estimate stays at zero: the dry run
        // of the enable, with the chosen score, is where the numbers come from.
        boolean gate = GATE.equals(recipe.id()) || IMPROVE.equals(recipe.id());
        Mono<AutomationDryRun.Estimate> estimate = gate || overrides.scoreName() == null
                ? Mono.just(emptyEstimate())
                : estimate(workspaceId, project.id(), templates(recipe(recipe.id(), project, overrides), project));
        boolean suiteHasCases = gate && suiteHasCases(workspaceId, project, overrides.testSuiteName());
        String blockedReason = gate
                ? (suiteHasCases
                        ? (state == Workflow.State.OFF && (overrides.promptName() == null || overrides.model() == null)
                                ? "name the prompt (overrides.prompt_name) and the model to run with (overrides.model)"
                                : null)
                        : "the suite has no cases yet: turn Collect on first")
                : (overrides.scoreName() == null && state == Workflow.State.OFF
                        ? "choose the judge score (overrides.score_name) from available_score_names"
                        : null);

        return estimate
                .map(figures -> Workflow.builder()
                        .recipe(recipe.id()).version(VERSION).name(recipe.name()).description(recipe.description())
                        .state(state).prerequisitesMet(!gate || suiteHasCases)
                        .blockedReason(blockedReason)
                        .automations(matched)
                        .estimate(figures)
                        .overrides(overrides)
                        .availableScoreNames(scoreNames)
                        .build());
    }

    /** Gate's prerequisite: the suite exists and holds at least one case. */
    private boolean suiteHasCases(String workspaceId, Project project, String suiteName) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            return dao.findByName(workspaceId, suiteName, project.id())
                    .or(() -> dao.findByName(workspaceId, suiteName, null));
        }).map(suite -> datasetItemService.getItems(1, 1, DatasetItemSearchCriteria.builder()
                .datasetId(suite.id()).experimentIds(Set.of()).entityType(EntityType.TRACE)
                .filters(List.of()).sortingFields(List.of()).truncate(true).build())
                .map(page -> page.total() > 0)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER)
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .blockOptional().orElse(false))
                .orElse(false);
    }

    private static AutomationDryRun.Estimate emptyEstimate() {
        return AutomationDryRun.Estimate.builder()
                .windowDays(AutomationService.ESTIMATE_WINDOW_DAYS).costUsd(java.math.BigDecimal.ZERO).build();
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
                .promptName(automation.steps().stream().map(Automation.Step::promptName).filter(Objects::nonNull)
                        .findFirst().orElse(defaults.promptName()))
                .model(automation.steps().stream().map(Automation.Step::model).filter(Objects::nonNull)
                        .findFirst().orElse(defaults.model()))
                .repository(automation.triggers().stream().map(Automation.Trigger::repository).filter(Objects::nonNull)
                        .findFirst().orElse(defaults.repository()))
                .notifyUrl(automation.steps().stream().map(Automation.Step::url).filter(Objects::nonNull)
                        .findFirst().orElse(defaults.notifyUrl()))
                .maxItemsPerDay(defaults.maxItemsPerDay() == null
                        ? null
                        : Optional.ofNullable(automation.limits()).map(Automation.Limits::maxItemsPerDay)
                                .orElse(defaults.maxItemsPerDay()))
                .metric(automation.steps().stream().map(Automation.Step::metric).filter(Objects::nonNull)
                        .findFirst().orElse(defaults.metric()))
                .optimizer(automation.steps().stream().map(Automation.Step::optimizer).filter(Objects::nonNull)
                        .findFirst().orElse(defaults.optimizer()))
                .n(automation.triggers().stream().map(Automation.Trigger::n).filter(Objects::nonNull)
                        .findFirst().orElse(defaults.n()))
                .minGain(defaults.minGain() == null
                        ? null
                        : automation.steps().stream()
                                .filter(candidate -> candidate.onlyIf() != null)
                                .flatMap(candidate -> candidate.onlyIf().groups().stream())
                                .flatMap(group -> group.conditions().stream())
                                .filter(gain -> "optimize.gain".equals(gain.stepOutput()))
                                .map(Automation.StepConditions.Condition::value)
                                .findFirst().orElse(defaults.minGain()))
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

    /** The recipe with the caller's overrides over its defaults. The judge has no default: see {@link #enable}. */
    Recipe recipe(String id, Project project, Workflow.Overrides overrides) {
        var given = Optional.ofNullable(overrides).orElse(Workflow.Overrides.builder().build());
        return switch (id) {
            case COLLECT -> new Recipe(COLLECT, "Collect",
                    "A judge score lands on a production trace: keep the trace in the regression suite when the "
                            + "score is below the threshold.",
                    Workflow.Overrides.builder()
                            .scoreName(given.scoreName())
                            .threshold(Optional.ofNullable(given.threshold()).orElse(DEFAULT_THRESHOLD))
                            .testSuiteName(Optional.ofNullable(given.testSuiteName()).orElse(suiteName(project)))
                            .maxItemsPerDay(
                                    Optional.ofNullable(given.maxItemsPerDay()).orElse(DEFAULT_MAX_ITEMS_PER_DAY))
                            .build());
            case GATE -> new Recipe(GATE, "Gate",
                    "A prompt version is saved, or CI runs: run the regression suite on it, post a GitHub check, "
                            + "and notify when there are regressions.",
                    Workflow.Overrides.builder()
                            .promptName(given.promptName())
                            .model(given.model())
                            .testSuiteName(Optional.ofNullable(given.testSuiteName()).orElse(suiteName(project)))
                            .repository(given.repository())
                            .notifyUrl(given.notifyUrl())
                            .build());
            case ALIGN -> new Recipe(ALIGN, "Align",
                    "A judge score lands on a production trace: send a few a day, at random, to a review queue "
                            + "so people can check the judge. Every N reviews, measure how often the judge and the "
                            + "reviewers agree, and ask for a revision when they drift apart.",
                    Workflow.Overrides.builder()
                            .scoreName(given.scoreName())
                            .threshold(Optional.ofNullable(given.threshold()).orElse(DEFAULT_THRESHOLD))
                            .annotationQueueName(
                                    Optional.ofNullable(given.annotationQueueName()).orElse(REVIEW_QUEUE_NAME))
                            .perDay(Optional.ofNullable(given.perDay()).orElse(DEFAULT_PER_DAY))
                            .n(Optional.ofNullable(given.n()).orElse(DEFAULT_REVIEWS))
                            .notifyUrl(given.notifyUrl())
                            .build());
            case IMPROVE -> new Recipe(IMPROVE, "Improve",
                    "The regression suite grows by N cases: optimize the prompt against it, and save the winner as "
                            + "a new version when it gains enough. Gate then tests that version.",
                    Workflow.Overrides.builder()
                            .promptName(given.promptName())
                            .model(given.model())
                            .testSuiteName(Optional.ofNullable(given.testSuiteName()).orElse(suiteName(project)))
                            .metric(Optional.ofNullable(given.metric()).orElse(DEFAULT_METRIC))
                            .optimizer(Optional.ofNullable(given.optimizer()).orElse(DEFAULT_OPTIMIZER))
                            .n(Optional.ofNullable(given.n()).orElse(DEFAULT_SUITE_GROWTH))
                            .minGain(Optional.ofNullable(given.minGain()).orElse(DEFAULT_MIN_GAIN))
                            .notifyUrl(given.notifyUrl())
                            .build());
            default -> throw refuse(UNKNOWN_RECIPE, "'%s' is not a recipe; known: %s".formatted(id, RECIPES));
        };
    }

    /** The automations a recipe creates for a project, as definitions the service resolves and stores. */
    List<Automation> templates(Recipe recipe, Project project) {
        var overrides = recipe.overrides().scoreName() == null
                ? recipe.overrides().toBuilder().scoreName(SCORE_NAME_PLACEHOLDER).build()
                : recipe.overrides();
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
            case GATE -> List.of(Automation.builder()
                    .projectId(project.id()).name("Test new versions of %s".formatted(
                            Optional.ofNullable(overrides.promptName()).orElse("the prompt")))
                    .enabled(true)
                    .recipe(Automation.Recipe.builder().id(GATE).version(VERSION).build())
                    .triggers(List.of(
                            Automation.Trigger.builder()
                                    .event(Automation.Event.PROMPT_COMMITTED.getValue())
                                    .promptName(overrides.promptName())
                                    .build(),
                            Automation.Trigger.builder()
                                    .event(Automation.Event.CI_RUN.getValue())
                                    .repository(overrides.repository())
                                    .build()))
                    .steps(gateSteps(overrides))
                    .limits(Automation.Limits.builder().maxRunsPerDay(50).build())
                    .build());
            case ALIGN -> List.of(
                    Automation.builder()
                            .projectId(project.id()).name("Random %s traces to review".formatted(overrides.scoreName()))
                            .enabled(true)
                            .recipe(Automation.Recipe.builder().id(ALIGN).version(VERSION).build())
                            .triggers(List.of(Automation.Trigger.builder()
                                    .event(Automation.Event.FEEDBACK_SCORE_CREATED.getValue())
                                    .scope(Automation.Scope.TRACE)
                                    // Any value of the judge's score: the sample has to be blind to the verdict.
                                    .conditions(any(overrides.scoreName()))
                                    .perDay(overrides.perDay())
                                    .build()))
                            .steps(List.of(Automation.Step.builder()
                                    .id("review").type(Automation.StepType.ANNOTATION_QUEUE_ADD.getValue())
                                    .annotationQueueName(overrides.annotationQueueName())
                                    .build()))
                            .limits(Automation.Limits.builder().maxItemsPerDay(overrides.perDay()).build())
                            .build(),
                    Automation.builder()
                            .projectId(project.id()).name("Check %s against reviewers".formatted(overrides.scoreName()))
                            .enabled(true)
                            .recipe(Automation.Recipe.builder().id(ALIGN).version(VERSION).build())
                            .triggers(List.of(Automation.Trigger.builder()
                                    .event(Automation.Event.ANNOTATION_QUEUE_SUBMITTED.getValue())
                                    .annotationQueueName(overrides.annotationQueueName())
                                    .n(overrides.n())
                                    .build()))
                            .steps(alignSteps(overrides))
                            .limits(Automation.Limits.builder().maxRunsPerDay(10).build())
                            .build());
            case IMPROVE -> List.of(Automation.builder()
                    .projectId(project.id()).name("Optimize %s as the suite grows".formatted(
                            Optional.ofNullable(overrides.promptName()).orElse("the prompt")))
                    .enabled(true)
                    .recipe(Automation.Recipe.builder().id(IMPROVE).version(VERSION).build())
                    .triggers(List.of(Automation.Trigger.builder()
                            .event(Automation.Event.TEST_SUITE_GREW.getValue())
                            .testSuiteName(overrides.testSuiteName())
                            .n(overrides.n())
                            .build()))
                    .steps(improveSteps(overrides))
                    .limits(Automation.Limits.builder().maxRunsPerDay(5).build())
                    .build());
            default -> throw refuse(UNKNOWN_RECIPE, "'%s' is not a recipe".formatted(recipe.id()));
        };
    }

    /** Run the suite, post the check, and notify only when something regressed. */
    private static List<Automation.Step> gateSteps(Workflow.Overrides overrides) {
        var steps = new java.util.ArrayList<Automation.Step>();
        steps.add(Automation.Step.builder()
                .id("run").type(Automation.StepType.TEST_SUITE_RUN.getValue())
                .testSuiteName(overrides.testSuiteName())
                .promptName(overrides.promptName())
                .model(overrides.model())
                .build());
        if (overrides.repository() != null) {
            steps.add(Automation.Step.builder()
                    .id("check").type(Automation.StepType.GITHUB_CHECK.getValue())
                    .repository(overrides.repository())
                    .context("opik/regression-suite")
                    .build());
        }
        if (overrides.notifyUrl() != null) {
            steps.add(Automation.Step.builder()
                    .id("notify").type(Automation.StepType.NOTIFY.getValue())
                    .url(overrides.notifyUrl())
                    .kind(overrides.notifyUrl().contains("hooks.slack.com")
                            ? Automation.NotifyKind.SLACK
                            : Automation.NotifyKind.WEBHOOK)
                    .onlyIf(Automation.StepConditions.builder().groups(List.of(
                            Automation.StepConditions.Group.builder().conditions(List.of(
                                    Automation.StepConditions.Condition.builder()
                                            .stepOutput("run.regressions")
                                            .operator(ScoreConditionOperator.GREATER_THAN).value(0.0).build()))
                                    .build()))
                            .build())
                    .build());
        }
        return steps;
    }

    /** Optimize, save the winner only when it gained enough, and tell someone when it did. */
    private static List<Automation.Step> improveSteps(Workflow.Overrides overrides) {
        var steps = new java.util.ArrayList<Automation.Step>();
        steps.add(Automation.Step.builder()
                .id("optimize").type(Automation.StepType.OPTIMIZER_RUN.getValue())
                .testSuiteName(overrides.testSuiteName())
                .promptName(overrides.promptName())
                .model(overrides.model())
                .metric(overrides.metric())
                .optimizer(overrides.optimizer())
                .build());
        steps.add(Automation.Step.builder()
                .id("save").type(Automation.StepType.PROMPT_VERSION_SAVE.getValue())
                .promptName(overrides.promptName())
                .onlyIf(atLeast("optimize.gain", overrides.minGain()))
                .build());
        if (overrides.notifyUrl() != null) {
            steps.add(Automation.Step.builder()
                    .id("notify").type(Automation.StepType.NOTIFY.getValue())
                    .url(overrides.notifyUrl())
                    .kind(notifyKind(overrides.notifyUrl()))
                    .message("Opik Improve saved a new version of %s".formatted(overrides.promptName()))
                    .onlyIf(atLeast("optimize.gain", overrides.minGain()))
                    .build());
        }
        return steps;
    }

    /** Measure agreement every N reviews; ask for a judge revision only when it fell under the floor. */
    private static List<Automation.Step> alignSteps(Workflow.Overrides overrides) {
        var steps = new java.util.ArrayList<Automation.Step>();
        steps.add(Automation.Step.builder()
                .id("agreement").type(Automation.StepType.JUDGE_AGREEMENT.getValue())
                .scoreName(overrides.scoreName())
                .annotationQueueName(overrides.annotationQueueName())
                .threshold(overrides.threshold())
                .build());
        if (overrides.notifyUrl() != null) {
            steps.add(Automation.Step.builder()
                    .id("revise").type(Automation.StepType.JUDGE_REVISE.getValue())
                    .scoreName(overrides.scoreName())
                    .url(overrides.notifyUrl())
                    .kind(notifyKind(overrides.notifyUrl()))
                    .onlyIf(Automation.StepConditions.builder().groups(List.of(
                            Automation.StepConditions.Group.builder().conditions(List.of(
                                    Automation.StepConditions.Condition.builder()
                                            .stepOutput("agreement.agreement")
                                            .operator(ScoreConditionOperator.LESS_THAN).value(DEFAULT_MIN_AGREEMENT)
                                            .build()))
                                    .build()))
                            .build())
                    .build());
        }
        return steps;
    }

    private static Automation.NotifyKind notifyKind(String url) {
        return url.contains("hooks.slack.com") ? Automation.NotifyKind.SLACK : Automation.NotifyKind.WEBHOOK;
    }

    private static Automation.StepConditions atLeast(String stepOutput, double value) {
        return Automation.StepConditions.builder().groups(List.of(
                Automation.StepConditions.Group.builder().conditions(List.of(
                        Automation.StepConditions.Condition.builder()
                                .stepOutput(stepOutput)
                                .operator(ScoreConditionOperator.GREATER_THAN_OR_EQUAL).value(value).build()))
                        .build()))
                .build();
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
                    case TEST_SUITE_RUN, GITHUB_CHECK, NOTIFY, OPTIMIZER_RUN, PROMPT_VERSION_SAVE, JUDGE_AGREEMENT,
                            JUDGE_REVISE ->
                        Mono.empty();
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
