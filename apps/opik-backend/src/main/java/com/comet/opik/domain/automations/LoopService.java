package com.comet.opik.domain.automations;

import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.Loop;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The loop as the running automations draw it: each automation is an edge from the object its trigger
 * reads to the objects its steps write, and the nodes are those objects. Nothing is stored; the picture
 * is derived from the automations and their run rollups over the window.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LoopService {

    static final String PRODUCTION = "production";

    private final @NonNull AutomationService automationService;
    private final @NonNull AutomationRunService runService;

    public Loop describe(@NonNull String workspaceId, @NonNull UUID projectId) {
        List<Automation> running = automationService.findByProject(workspaceId, projectId, true);
        int windowDays = AutomationService.ESTIMATE_WINDOW_DAYS;
        var stats = runService.stats(workspaceId, running.stream().map(Automation::id).toList(),
                Instant.now().minus(Duration.ofDays(windowDays)), windowDays);

        Map<String, Loop.Node> nodes = new LinkedHashMap<>();
        nodes.put(PRODUCTION, new Loop.Node(PRODUCTION, "project", "production traces", projectId));
        var edges = new ArrayList<Loop.Edge>();
        for (var automation : running) {
            var trigger = automation.triggers().getFirst();
            String from = node(nodes, triggerSource(trigger));
            var to = automation.steps().stream()
                    .map(LoopService::stepTarget)
                    .filter(Objects::nonNull)
                    .map(target -> node(nodes, target))
                    .distinct()
                    .toList();
            var produced = stats.getOrDefault(automation.id(), AutomationRunService.emptyStats(windowDays));
            edges.add(Loop.Edge.builder()
                    .automationId(automation.id()).name(automation.name())
                    .recipe(automation.recipe() == null ? null : automation.recipe().id())
                    .from(from).to(to)
                    .produced(Map.of("fired", produced.fired(), "matched", produced.matched(),
                            "items_written", produced.itemsWritten(), "steps_failed", produced.stepsFailed()))
                    .build());
        }
        return Loop.builder().projectId(projectId).windowDays(windowDays)
                .nodes(List.copyOf(nodes.values())).edges(edges).build();
    }

    private static String node(Map<String, Loop.Node> nodes, Loop.Node candidate) {
        nodes.putIfAbsent(candidate.id(), candidate);
        return candidate.id();
    }

    private static Loop.Node triggerSource(Automation.Trigger trigger) {
        return switch (Objects.requireNonNull(Automation.Event.fromString(trigger.event()))) {
            case FEEDBACK_SCORE_CREATED -> new Loop.Node(PRODUCTION, "project", "production traces", null);
            case PROMPT_COMMITTED -> prompt(trigger.promptId(), trigger.promptName());
            case CI_RUN -> new Loop.Node("ci:" + trigger.repository(), "ci",
                    trigger.repository() == null ? "CI" : trigger.repository(), null);
            case TEST_SUITE_GREW -> suite(trigger.testSuiteId(), trigger.testSuiteName());
            case ANNOTATION_QUEUE_SUBMITTED -> queue(trigger.annotationQueueId(), trigger.annotationQueueName());
            case EXPERIMENT_FINISHED, OPTIMIZATION_FINISHED -> new Loop.Node(PRODUCTION, "project",
                    "production traces", null);
        };
    }

    private static Loop.Node stepTarget(Automation.Step step) {
        return switch (Objects.requireNonNull(Automation.StepType.fromString(step.type()))) {
            case TEST_SUITE_ADD, TEST_SUITE_RUN -> suite(step.testSuiteId(), step.testSuiteName());
            case DATASET_ADD -> new Loop.Node("dataset:" + step.datasetId(), "dataset", step.datasetName(),
                    step.datasetId());
            case ANNOTATION_QUEUE_ADD -> queue(step.annotationQueueId(), step.annotationQueueName());
            case OPTIMIZER_RUN, PROMPT_VERSION_SAVE -> prompt(step.promptId(), step.promptName());
            case JUDGE_AGREEMENT, JUDGE_REVISE -> new Loop.Node("judge:" + step.scoreName(), "judge",
                    step.scoreName(), null);
            case GITHUB_CHECK -> new Loop.Node("ci:" + step.repository(), "ci", step.repository(), null);
            case NOTIFY -> null;
        };
    }

    private static Loop.Node suite(UUID id, String name) {
        return new Loop.Node("suite:" + (id != null ? id : name), "test_suite", name, id);
    }

    private static Loop.Node queue(UUID id, String name) {
        return new Loop.Node("queue:" + (id != null ? id : name), "annotation_queue", name, id);
    }

    private static Loop.Node prompt(UUID id, String name) {
        return new Loop.Node("prompt:" + (id != null ? id : name), "prompt", name, id);
    }
}
