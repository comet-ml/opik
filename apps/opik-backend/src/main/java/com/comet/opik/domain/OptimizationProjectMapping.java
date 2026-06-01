package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * Result of {@link OptimizationDAO#computeOptimizationProjectMappingViaExperiments}: one row per
 * orphan optimization whose experiments resolve to a non-empty {@code project_id}. Holds the
 * inferred {@code projectId} to assign. {@code distinctProjectCount} is the number of projects the
 * optimization's experiments reference; when it is greater than one, {@code projectId} is the
 * dominant project, chosen by {@code (count DESC, last_activity DESC, project_id ASC)} — same shape
 * the dataset migration adopted in OPIK-6701.
 *
 * <p>{@code projectBreakdown} lists the per-project experiment counts in the same order (for
 * example {@code "p1=5,p2=3,p3=1"}) and is included in the log entry written for each dominant
 * assignment. It is empty for the rows the service builds for Default Project assignments
 * (certain-deleted-project or Path B no-inference fall-throughs).
 *
 * <p>No-inference orphans (Path A returned no rows) are absent from the result; the service detects
 * them by diffing against the workspace's orphan optimization set and falls back to Path B
 * ({@code datasets.project_id} lookup) before routing to the Default Project.
 */
@Builder(toBuilder = true)
public record OptimizationProjectMapping(
        @NonNull UUID optimizationId,
        @NonNull UUID projectId,
        long distinctProjectCount,
        @NonNull String projectBreakdown) {
}
