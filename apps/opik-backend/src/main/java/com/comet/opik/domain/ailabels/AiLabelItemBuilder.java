package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.inference.BatchDecideItem;
import com.comet.opik.infrastructure.inference.BatchDecideRequest;
import com.comet.opik.infrastructure.inference.BatchGenerateItem;
import com.comet.opik.infrastructure.inference.BatchGenerateRequest;
import com.comet.opik.infrastructure.inference.BatchItem;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.Message;
import com.comet.opik.infrastructure.inference.Question;
import com.google.common.base.Preconditions;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds batch items from a rendered trace and the tasks it is missing. Each trace is rendered once per kind of task
 * (with or without {@code {{history}}}) and shared by those tasks' items: its decide tasks that share a {@code cache} setting are packed first-fit into items of at most 32 questions
 * (deterministic, though not always the fewest items), and each generate
 * task is an item of its own.
 */
@Singleton
public class AiLabelItemBuilder {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            Pattern.quote(AiLabelTask.STATE_PLACEHOLDER) + "|" + Pattern.quote(AiLabelTask.HISTORY_PLACEHOLDER));

    /**
     * Packing is first-fit in task name order, never splitting a task's questions, so the same task set always packs
     * the same way. Ids: {@code <trace_id>} for a trace's only decide item per {@code cache} setting,
     * {@code <trace_id>:p<n>} when there are more, and {@code <trace_id>:<task>} for a generate item.
     */
    public List<AiLabelItemPlan> plan(@NonNull UUID traceId, @NonNull Collection<AiLabelTask> tasks) {
        var sorted = tasks.stream().sorted(Comparator.comparing(AiLabelTask::name)).toList();
        var plans = new ArrayList<AiLabelItemPlan>();
        for (var cache : List.of(false, true)) {
            var bins = pack(sorted.stream()
                    .filter(task -> task.endpoint() == AiLabelEndpoint.DECIDE && task.cache() == cache)
                    .toList());
            for (int index = 0; index < bins.size(); index++) {
                var itemId = bins.size() == 1 ? traceId.toString() : "%s:p%d".formatted(traceId, index);
                plans.add(AiLabelItemPlan.builder()
                        .itemId(itemId)
                        .endpoint(AiLabelEndpoint.DECIDE)
                        .cache(cache)
                        .tasks(bins.get(index))
                        .build());
            }
        }
        sorted.stream()
                .filter(task -> task.endpoint() == AiLabelEndpoint.GENERATE)
                .map(task -> AiLabelItemPlan.builder()
                        .itemId("%s:%s".formatted(traceId, task.name()))
                        .endpoint(AiLabelEndpoint.GENERATE)
                        .cache(task.cache())
                        .tasks(List.of(task))
                        .build())
                .forEach(plans::add);
        return plans;
    }

    /** {@code missing} are the tasks {@code rendered} is for: all with {@code {{history}}}, or all without. */
    public List<AiLabelItem> build(@NonNull String workspaceId, @NonNull RenderOutcome.Rendered rendered,
            @NonNull Collection<AiLabelTask> missing) {
        for (var task : missing) {
            Preconditions.checkArgument(task.usesHistory() == rendered.withHistory(),
                    "task rendered without its history, or with one it doesn't use, task '%s'", task.name());
        }
        return plan(rendered.trace().traceId(), missing).stream()
                .map(plan -> {
                    var payload = payload(plan, rendered);
                    return AiLabelItem.builder()
                            .workspaceId(workspaceId)
                            .trace(rendered.trace())
                            .plan(plan)
                            .compactionLevel(rendered.compactionLevel())
                            .payload(payload)
                            .bytes(InferenceJson.writeBytes(payload).length)
                            .build();
                })
                .toList();
    }

    /** The submit for one decide job: {@code org_id} and {@code workspace_id} are both the workspace id. */
    public BatchDecideRequest decideRequest(@NonNull String workspaceId, boolean cache,
            @NonNull List<AiLabelItem> items) {
        checkJob(workspaceId, new AiLabelItemPlan.JobKey(AiLabelEndpoint.DECIDE, cache), items);
        return BatchDecideRequest.builder()
                .orgId(workspaceId)
                .workspaceId(workspaceId)
                .cache(cache)
                .items(items.stream().map(item -> (BatchDecideItem) item.payload()).toList())
                .build();
    }

    /** The submit for one generate job: {@code org_id} and {@code workspace_id} are both the workspace id. */
    public BatchGenerateRequest generateRequest(@NonNull String workspaceId, boolean cache,
            @NonNull List<AiLabelItem> items) {
        checkJob(workspaceId, new AiLabelItemPlan.JobKey(AiLabelEndpoint.GENERATE, cache), items);
        return BatchGenerateRequest.builder()
                .orgId(workspaceId)
                .workspaceId(workspaceId)
                .cache(cache)
                .items(items.stream().map(item -> (BatchGenerateItem) item.payload()).toList())
                .build();
    }

    private static List<List<AiLabelTask>> pack(List<AiLabelTask> tasks) {
        var bins = new ArrayList<List<AiLabelTask>>();
        var counts = new ArrayList<Integer>();
        for (var task : tasks) {
            var placed = false;
            for (int index = 0; index < bins.size() && !placed; index++) {
                if (counts.get(index) + task.questionCount() <= BatchDecideItem.MAX_QUESTIONS) {
                    bins.get(index).add(task);
                    counts.set(index, counts.get(index) + task.questionCount());
                    placed = true;
                }
            }
            if (!placed) {
                bins.add(new ArrayList<>(List.of(task)));
                counts.add(task.questionCount());
            }
        }
        return bins;
    }

    private static BatchItem payload(AiLabelItemPlan plan, RenderOutcome.Rendered rendered) {
        return switch (plan.endpoint()) {
            case DECIDE -> {
                var questions = new TreeMap<String, Question>();
                plan.tasks().forEach(task -> task.questions()
                        .forEach((question, definition) -> questions.put(task.questionId(question), definition)));
                yield BatchDecideItem.builder()
                        .itemId(plan.itemId())
                        .state(rendered.state())
                        .questions(questions)
                        .build();
            }
            case GENERATE -> {
                var task = plan.tasks().getFirst();
                var messages = new ArrayList<Message>();
                if (task.prompt().system() != null) {
                    messages.add(Message.builder().role(Message.Role.SYSTEM).content(task.prompt().system()).build());
                }
                var user = fill(task.prompt().user(), rendered);
                messages.add(Message.builder().role(Message.Role.USER).content(user).build());
                yield BatchGenerateItem.builder()
                        .itemId(plan.itemId())
                        .messages(messages)
                        .schema(task.schema())
                        .maxTokens(task.effectiveMaxTokens())
                        .temperature(task.effectiveTemperature())
                        .build();
            }
        };
    }

    /**
     * One pass over the template, so a {@code {{history}}} inside the trace's own content is left as text rather than
     * replaced.
     */
    private static String fill(String template, RenderOutcome.Rendered rendered) {
        var matcher = PLACEHOLDER.matcher(template);
        var filled = new StringBuilder();
        while (matcher.find()) {
            var value = matcher.group().equals(AiLabelTask.STATE_PLACEHOLDER) ? rendered.state() : rendered.history();
            matcher.appendReplacement(filled, Matcher.quoteReplacement(AiLabelRenderer.canonicalJson(value)));
        }
        return matcher.appendTail(filled).toString();
    }

    private static void checkJob(String workspaceId, AiLabelItemPlan.JobKey jobKey, List<AiLabelItem> items) {
        for (var item : items) {
            Preconditions.checkArgument(item.workspaceId().equals(workspaceId),
                    "item belongs to another workspace, itemId '%s'", item.plan().itemId());
            Preconditions.checkArgument(item.plan().jobKey().equals(jobKey),
                    "item belongs to another job, itemId '%s', jobKey '%s'", item.plan().itemId(),
                    item.plan().jobKey());
        }
    }
}
