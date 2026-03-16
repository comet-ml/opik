package com.comet.opik.domain.experiments.aggregations;

import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record AggregationBranchCountsCriteria(
        Set<UUID> experimentIds,
        UUID datasetId,
        UUID id,
        Set<UUID> idsList) {

    public static AggregationBranchCountsCriteria empty() {
        return AggregationBranchCountsCriteria.builder().build();
    }
}
