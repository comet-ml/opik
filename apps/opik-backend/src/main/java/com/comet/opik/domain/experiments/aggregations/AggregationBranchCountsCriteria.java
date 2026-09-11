package com.comet.opik.domain.experiments.aggregations;

import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * Criteria narrowing the aggregated/non-aggregated experiment counts that decide which branches of the
 * experiment queries are rendered.
 * <p>
 * {@code projectId} narrows only the non-aggregated (raw fallback) count. Without it the count is
 * workspace-wide, so a single non-aggregated experiment anywhere in the workspace keeps the raw branch for
 * every request in that workspace, even when no experiment in the requested project needs it.
 * <p>
 * An experiment belongs to a project either through its own {@code project_id} or through the projects of the
 * traces its items reference - the raw branch matches on the concatenation of both. Most experiments carry no
 * {@code project_id}, so the trace-derived side cannot be dropped; and a substantial minority carry a
 * {@code project_id} that none of their traces point at, so the {@code project_id} side cannot be dropped
 * either. Both are therefore required for the count to be correct: under-counting would drop the raw branch
 * and silently omit matching experiments from the response.
 */
@Builder(toBuilder = true)
public record AggregationBranchCountsCriteria(
        Set<UUID> experimentIds,
        UUID datasetId,
        UUID id,
        Set<UUID> idsList,
        UUID projectId) {

    public static AggregationBranchCountsCriteria empty() {
        return AggregationBranchCountsCriteria.builder().build();
    }
}
