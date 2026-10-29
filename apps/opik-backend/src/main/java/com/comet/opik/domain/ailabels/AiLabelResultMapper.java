package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.inference.Answer;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.ItemResponse;
import com.comet.opik.infrastructure.inference.ItemResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maps one item's result to label and status rows. Each status row is built from the task's in-flight row, so the
 * attempt, versions, job and {@code compaction_level} recorded at submit carry over. A task is {@code done} only with all of its
 * values: an answer or output that is missing fails it as {@code missing_answer}, retryable.
 */
@Singleton
public class AiLabelResultMapper {

    public static final String MISSING_ANSWER = "missing_answer";
    public static final String CANCELLED = "cancelled";

    /**
     * @param plan  the item's plan, rebuilt from the job's in-flight rows
     * @param items the item's in-flight rows, one per task of the plan
     */
    public AiLabelResults map(@NonNull ItemResult result, @NonNull AiLabelItemPlan plan,
            @NonNull List<AiLabelJobItem> items) {
        Preconditions.checkArgument(result.itemId().equals(plan.itemId()),
                "result is for another item, itemId '%s', planItemId '%s'", result.itemId(), plan.itemId());
        var itemsByTask = items.stream()
                .collect(Collectors.toMap(AiLabelJobItem::task, Function.identity()));
        var taskNames = plan.tasks().stream().map(AiLabelTask::name).collect(Collectors.toSet());
        Preconditions.checkArgument(itemsByTask.keySet().equals(taskNames),
                "in-flight rows don't match the item's tasks, itemId '%s'", plan.itemId());

        if (result.error() != null) {
            return failAll(plan, itemsByTask, result.error().code(), result.error().retryable());
        }
        if (result.response() == null) {
            return failAll(plan, itemsByTask, CANCELLED, true);
        }
        var labels = new ArrayList<AiLabel>();
        var statuses = new ArrayList<AiLabelStatus>();
        for (var task : plan.tasks()) {
            var row = itemsByTask.get(task.name());
            var taskLabels = switch (plan.endpoint()) {
                case DECIDE -> decideLabels(task, row, result.response());
                case GENERATE -> generateLabels(task, row, result.response());
            };
            if (taskLabels == null) {
                statuses.add(row.failed(MISSING_ANSWER, true));
            } else {
                labels.addAll(taskLabels);
                statuses.add(row.done());
            }
        }
        return AiLabelResults.builder().labels(labels).statuses(statuses).build();
    }

    /** One row per question, or null when any of the task's questions has no answer. */
    private static List<AiLabel> decideLabels(AiLabelTask task, AiLabelJobItem row, ItemResponse response) {
        if (!(response instanceof ItemResponse.DecideResponse decide)) {
            return null;
        }
        var answers = decide.result().answers();
        var labels = new ArrayList<AiLabel>();
        for (var question : task.questions().keySet()) {
            var answer = answers.get(task.questionId(question));
            if (answer == null) {
                return null;
            }
            labels.add(decideLabel(row, question, answer));
        }
        return labels;
    }

    private static AiLabel decideLabel(AiLabelJobItem row, String key, Answer answer) {
        var label = baseLabel(row, key);
        return switch (answer) {
            case Answer.NoulAnswer noul -> label
                    .answerType(AiLabel.AnswerType.NOUL)
                    .noul((float) noul.noul())
                    .build();
            case Answer.ChoiceAnswer choice -> label
                    .answerType(AiLabel.AnswerType.CHOICE)
                    .choice(choice.choice())
                    .confidence((float) choice.confidence())
                    .probabilities(toFloats(choice.probabilities()))
                    .build();
            case Answer.ScoreAnswer score -> label
                    .answerType(AiLabel.AnswerType.SCORE)
                    .score((float) score.score())
                    .confidence((float) score.confidence())
                    .probabilities(toFloats(score.probabilities()))
                    .legend(score.legend().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, entry -> legendText(entry.getValue()))))
                    .build();
        };
    }

    /** One {@code output} row, or null when there is no output or it lacks a property its schema requires. */
    private static List<AiLabel> generateLabels(AiLabelTask task, AiLabelJobItem row, ItemResponse response) {
        if (!(response instanceof ItemResponse.GenerateResponse generate)) {
            return null;
        }
        var output = generate.result().output();
        if (output == null || output.isNull() || !hasRequired(task.schema(), output)) {
            return null;
        }
        return List.of(baseLabel(row, AiLabel.OUTPUT_KEY)
                .answerType(AiLabel.AnswerType.OUTPUT)
                .output(InferenceJson.canonical(output))
                .build());
    }

    private static boolean hasRequired(JsonNode schema, JsonNode output) {
        var required = schema.path("required");
        if (required.isEmpty()) {
            return true;
        }
        if (!output.isObject()) {
            return false;
        }
        for (var property : required) {
            if (!output.has(property.textValue())) {
                return false;
            }
        }
        return true;
    }

    private static AiLabel.AiLabelBuilder baseLabel(AiLabelJobItem row, String key) {
        return AiLabel.builder()
                .workspaceId(row.workspaceId())
                .projectId(row.projectId())
                .task(row.task())
                .traceId(row.traceId())
                .key(key)
                .taskVersion(row.taskVersion())
                .probabilities(Map.of())
                .legend(Map.of())
                .output("");
    }

    private static Map<String, Float> toFloats(Map<String, Double> values) {
        return values.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().floatValue()));
    }

    private static String legendText(JsonNode value) {
        return value.isTextual() ? value.textValue() : InferenceJson.canonical(value);
    }

    private static AiLabelResults failAll(AiLabelItemPlan plan, Map<String, AiLabelJobItem> itemsByTask,
            String errorCode, boolean retryable) {
        return AiLabelResults.builder()
                .labels(List.of())
                .statuses(plan.tasks().stream()
                        .map(task -> itemsByTask.get(task.name()).failed(errorCode, retryable))
                        .toList())
                .build();
    }
}
