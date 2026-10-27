package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.inference.BatchItem;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

/**
 * A built batch item for one trace: its plan, the wire payload, and the payload's serialized size, so the caller can
 * fill jobs up to {@code maxJobBytes}.
 */
@Builder(toBuilder = true)
public record AiLabelItem(
        @NonNull String workspaceId,
        @NonNull TraceRef trace,
        @NonNull AiLabelItemPlan plan,
        @NonNull String compactionLevel,
        @NonNull BatchItem payload,
        int bytes) {

    public int engineCalls() {
        return plan.engineCalls();
    }

    /**
     * The item's in-flight rows, written with its job: one per task, at the task's current version.
     *
     * @param attemptByTask the attempt to record for each of the item's tasks
     */
    public List<AiLabelJobItem> jobItems(@NonNull String jobId, int rendererVersion,
            @NonNull Map<String, Integer> attemptByTask) {
        return plan.tasks().stream()
                .map(task -> {
                    var attempt = attemptByTask.get(task.name());
                    if (attempt == null) {
                        throw new IllegalArgumentException(
                                "no attempt given for task, task '%s', itemId '%s'".formatted(task.name(),
                                        plan.itemId()));
                    }
                    return AiLabelJobItem.builder()
                            .jobId(jobId)
                            .workspaceId(workspaceId)
                            .projectId(trace.projectId())
                            .traceId(trace.traceId())
                            .traceCreatedAt(trace.createdAt())
                            .task(task.name())
                            .taskVersion(task.version())
                            .rendererVersion(rendererVersion)
                            .compactionLevel(compactionLevel)
                            .attempt(attempt)
                            .build();
                })
                .toList();
    }
}
