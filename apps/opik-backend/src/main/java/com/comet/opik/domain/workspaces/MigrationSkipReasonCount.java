package com.comet.opik.domain.workspaces;

import lombok.Builder;
import lombok.NonNull;

/**
 * Result of grouping trapped workspaces by skip reason (e.g. {@code deleted_project},
 * {@code all_ambiguous}, {@code default_project_missing}). Drives the per-reason tagging on the
 * trapped-workspaces gauge.
 */
@Builder(toBuilder = true)
public record MigrationSkipReasonCount(@NonNull String reason, long count) {
}
