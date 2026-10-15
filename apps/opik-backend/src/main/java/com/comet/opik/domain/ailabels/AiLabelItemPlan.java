package com.comet.opik.domain.ailabels;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * Which tasks one batch item carries, and its {@code item_id}. Plans depend only on the trace id and the task set, so
 * the loader can rebuild an item's tasks from a job's pending rows.
 */
@Builder(toBuilder = true)
public record AiLabelItemPlan(@NonNull String itemId, @NonNull AiLabelEndpoint endpoint, boolean cache,
        @NonNull List<AiLabelTask> tasks) {

    /** One job holds one endpoint and one {@code cache} setting, since {@code cache} is set on the whole submit. */
    public record JobKey(@NonNull AiLabelEndpoint endpoint, boolean cache) {
    }

    public AiLabelItemPlan {
        tasks = List.copyOf(tasks);
    }

    /** What the item costs against the in-flight budget: its question count, or 1 for a generate item. */
    public int engineCalls() {
        return switch (endpoint) {
            case DECIDE -> tasks.stream().mapToInt(AiLabelTask::questionCount).sum();
            case GENERATE -> 1;
        };
    }

    public JobKey jobKey() {
        return new JobKey(endpoint, cache);
    }
}
