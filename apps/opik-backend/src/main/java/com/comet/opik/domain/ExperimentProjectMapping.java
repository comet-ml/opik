package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * Result of {@link ExperimentDAO#computeExperimentProjectMapping()}: one row per orphan
 * experiment, holding the inferred {@code projectId} to assign.
 *
 * <p>{@code projectId} is the only nullable field — {@code null} means no inference was
 * possible (no trace items reference any project, so {@code distinctProjectCount == 0}).
 * When {@code distinctProjectCount == 1} it is the sole referenced project; when
 * {@code > 1} it is the dominant project, chosen by
 * {@code (count DESC, last_activity DESC, project_id ASC)}.
 *
 * <p>{@code projectBreakdown} lists the per-project trace counts in the same order (e.g.
 * {@code "p1=5,p2=3,p3=1"}). Empty for no-inference rows and for Default-Project fallbacks
 * synthesized by the service.
 */
@Builder(toBuilder = true)
public record ExperimentProjectMapping(
        @NonNull UUID experimentId,
        UUID projectId,
        long distinctProjectCount,
        @NonNull String projectBreakdown) {
}
