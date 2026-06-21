package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * Result of {@link ExperimentDAO#computePromptProjectClassification(java.util.Set)}: one row per
 * referenced prompt with a usable inferred project. Sister to {@link DatasetProjectMapping}.
 *
 * <p>When {@code distinctProjectCount == 1}, {@code projectId} is the sole referenced project;
 * when {@code > 1}, it is the dominant project, chosen by {@code (count DESC, last_activity DESC,
 * project_id ASC)}. {@code projectBreakdown} lists the per-project experiment counts in the same
 * order (for example {@code "p1=5,p2=3,p3=1"}) and is included in the dominant-assignment log
 * entry.
 */
@Builder(toBuilder = true)
public record PromptProjectClassification(
        @NonNull UUID promptId,
        @NonNull UUID projectId,
        long distinctProjectCount,
        @NonNull String projectBreakdown) {
}
