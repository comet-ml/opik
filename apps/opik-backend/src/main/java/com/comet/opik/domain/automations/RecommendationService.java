package com.comet.opik.domain.automations;

import com.comet.opik.api.Project;
import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.Recommendation;
import com.comet.opik.api.automations.Workflow;
import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.evaluators.AutomationRuleDAO;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Ranked next steps for a project, computed from what already runs: setup steps whose prerequisites are
 * met first, then upkeep, then setup steps still blocked. A recommendation is covered, and dropped, when
 * any running automation does its job, whether a recipe or the user's own made it. The list is never
 * empty: with everything on, it says to check the first results.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class RecommendationService {

    static final Duration DISMISSAL = Duration.ofDays(30);
    static final Duration FIRST_RESULTS = Duration.ofDays(7);

    private final @NonNull AutomationService automationService;
    private final @NonNull WorkflowService workflowService;
    private final @NonNull AutomationRunService runService;
    private final @NonNull AutomationTargetHistoryDAO targetHistoryDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull TransactionTemplate transactionTemplate;

    public Mono<List<Recommendation>> list(@NonNull String workspaceId, @NonNull UUID projectId) {
        Project project = projectService.get(projectId, workspaceId);
        List<Automation> running = automationService.findByProject(workspaceId, projectId, true);
        boolean collects = running.stream().anyMatch(automation -> hasStep(automation,
                Automation.StepType.TEST_SUITE_ADD, Automation.StepType.DATASET_ADD));
        boolean samples = running.stream().anyMatch(automation -> hasStep(automation,
                Automation.StepType.ANNOTATION_QUEUE_ADD)
                && automation.triggers().stream().anyMatch(trigger -> trigger.perDay() != null));
        boolean hasJudge = transactionTemplate.inTransaction(READ_ONLY, handle -> handle
                .attach(AutomationRuleDAO.class)
                .findCount(Set.of(projectId), workspaceId, AutomationRule.AutomationRuleAction.EVALUATOR)) > 0;
        Set<UUID> dismissed = transactionTemplate.inTransaction(READ_ONLY, handle -> handle
                .attach(AutomationRecommendationDismissalDAO.class)
                .findDismissed(workspaceId, projectId, Instant.now()));

        return workflowService.list(workspaceId, projectId)
                .zipWith(targetHistoryDAO.countWrittenSince(projectId,
                        Instant.now().minus(Duration.ofDays(AutomationService.ESTIMATE_WINDOW_DAYS)))
                        .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                                .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER)))
                .map(tuple -> {
                    var workflows = tuple.getT1();
                    long casesWritten = tuple.getT2();
                    var collect = byRecipe(workflows, WorkflowService.COLLECT);
                    var align = byRecipe(workflows, WorkflowService.ALIGN);
                    var out = new ArrayList<Recommendation>();

                    if (!collects) {
                        out.add(setup(workspaceId, projectId, collect,
                                ("Nothing adds production failures to a test suite. In %d days %d traces were scored "
                                        + "'%s', %d of them below %s.").formatted(
                                                collect.estimate().windowDays(), collect.estimate().fired(),
                                                collect.overrides().scoreName(), collect.estimate().matched(),
                                                collect.overrides().threshold()),
                                Map.of("scored", collect.estimate().fired(), "below_threshold",
                                        collect.estimate().matched(), "window_days", collect.estimate().windowDays(),
                                        "has_judge", hasJudge)));
                    }
                    // Align ranks after Collect, and last of all while no judge decides anything here.
                    var alignRecommendation = samples
                            ? null
                            : setup(workspaceId, projectId, align,
                                    (hasJudge
                                            ? "A judge decides what gets collected, and nobody checks it. "
                                            : "No judge runs on this project yet. ")
                                            + "Send %d scored traces a day to '%s' so reviewers can check the judge."
                                                    .formatted(align.overrides().perDay(),
                                                            align.overrides().annotationQueueName()),
                                    Map.of("scored", align.estimate().fired(), "has_judge", hasJudge,
                                            "window_days", align.estimate().windowDays()));
                    if (alignRecommendation != null && hasJudge) {
                        out.add(alignRecommendation);
                    }
                    if (out.isEmpty() || alignRecommendation == null) {
                        out.add(review(workspaceId, projectId, running, casesWritten));
                    }
                    if (alignRecommendation != null && !hasJudge) {
                        out.add(alignRecommendation);
                    }
                    return out.stream().filter(recommendation -> !dismissed.contains(recommendation.id())).toList();
                })
                .map(list -> list.isEmpty()
                        ? List.of(review(workspaceId, projectId, running, 0L))
                        : list);
    }

    public void dismiss(@NonNull String workspaceId, @NonNull String userName, @NonNull UUID projectId,
            @NonNull UUID recommendationId) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(AutomationRecommendationDismissalDAO.class).dismiss(workspaceId, recommendationId, projectId,
                    Instant.now().plus(DISMISSAL), userName);
            return null;
        });
        log.info("Dismissed recommendation '{}' for project '{}'", recommendationId, projectId);
    }

    private Recommendation setup(String workspaceId, UUID projectId, Workflow workflow, String reason,
            Map<String, Object> evidence) {
        var data = Map.of("recipe", workflow.recipe(), "project_id", projectId, "overrides", workflow.overrides());
        return Recommendation.builder()
                .id(id(workspaceId, projectId, Recommendation.Kind.SETUP, workflow.recipe()))
                .kind(Recommendation.Kind.SETUP)
                .workflow(workflow.recipe())
                .reason(reason)
                .evidence(JsonUtils.valueToTree(evidence))
                .apply(Recommendation.Apply.builder().operation("workflow.enable").data(JsonUtils.valueToTree(data))
                        .build())
                .build();
    }

    private Recommendation review(String workspaceId, UUID projectId, List<Automation> running, long casesWritten) {
        var firstRun = runService.firstRunAt(workspaceId, projectId);
        boolean young = firstRun.map(at -> at.isAfter(Instant.now().minus(FIRST_RESULTS))).orElse(true);
        String reason = young
                ? "Everything that can run is on, with under %d days of runs. Check the first results before tuning."
                        .formatted(FIRST_RESULTS.toDays())
                : "Automation wrote %d cases in %d days across %d automations. Check that the suite is being run."
                        .formatted(casesWritten, AutomationService.ESTIMATE_WINDOW_DAYS, running.size());
        return Recommendation.builder()
                .id(id(workspaceId, projectId, Recommendation.Kind.REVIEW, "first-results"))
                .kind(Recommendation.Kind.REVIEW)
                .reason(reason)
                .evidence(JsonUtils.valueToTree(Map.of("running", running.size(), "cases_written_14d", casesWritten,
                        "first_run_at", firstRun.map(Instant::toString).orElse(""))))
                .build();
    }

    private static Workflow byRecipe(List<Workflow> workflows, String recipe) {
        return workflows.stream().filter(workflow -> recipe.equals(workflow.recipe())).findFirst().orElseThrow();
    }

    private static boolean hasStep(Automation automation, Automation.StepType... types) {
        var wanted = Set.of(types);
        return automation.steps().stream()
                .map(step -> Automation.StepType.fromString(step.type()))
                .filter(Objects::nonNull)
                .anyMatch(wanted::contains);
    }

    /** Deterministic, so dismissing one dismisses every recomputation of it. */
    static UUID id(String workspaceId, UUID projectId, Recommendation.Kind kind, String key) {
        return UUID.nameUUIDFromBytes("%s:%s:%s:%s".formatted(workspaceId, projectId, kind.getValue(), key)
                .getBytes(StandardCharsets.UTF_8));
    }
}
