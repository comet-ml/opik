package com.comet.opik.domain.experiments.aggregations;

import lombok.Builder;
import lombok.NonNull;

import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

/**
 * Utility models for experiment aggregates operations.
 */
public class ExperimentAggregatesUtils {

    /**
     * Result of a batch processing operation.
     *
     * @param processedCount Number of items processed
     * @param lastCursor     The cursor position after processing
     */
    @Builder
    public record BatchResult(long processedCount, UUID lastCursor) {
    }

    /**
     * Helper for converting maps to parallel arrays for ClickHouse binding.
     * ClickHouse requires maps to be bound as separate key and value arrays.
     *
     * @param keys   Array of map keys
     * @param values Array of map values
     * @param <K>    Key type
     * @param <V>    Value type
     */
    public record MapArrays<K, V>(K[] keys, V[] values) {
    }

    /**
     * Single project_id label written to {@code experiment_aggregates} /
     * {@code experiment_item_aggregates} rows. Prefers the experiment's stored {@code project_id}
     * (set at creation for V2-native experiments, set to the dominant project by the
     * experiment-project migration for legacy ones); falls back to
     * {@link #fallbackLabelProjectId(Set)} when null. The fallback window is transient:
     * ReplacingMergeTree dedup overwrites the row once migration assigns a project.
     */
    public static UUID resolveLabelProjectId(UUID experimentProjectId, @NonNull Set<UUID> projectIds) {
        return experimentProjectId != null ? experimentProjectId : fallbackLabelProjectId(projectIds);
    }

    /**
     * Deterministic label for the unmigrated-V1 window: smallest project_id by lexicographic
     * string comparison — the same ordering ClickHouse uses for {@code project_id ASC} on
     * FixedString(36), so the system's "pick one project from a set" decisions all agree.
     * Callers must ensure {@code projectIds} is non-empty.
     */
    public static UUID fallbackLabelProjectId(@NonNull Set<UUID> projectIds) {
        return projectIds.stream().min(Comparator.comparing(UUID::toString)).orElseThrow();
    }
}
