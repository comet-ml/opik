package com.comet.opik.domain.experiments.aggregations;

import lombok.Builder;

@Builder(toBuilder = true)
public record AggregatedExperimentCounts(long aggregated, long notAggregated) {
    public static final AggregatedExperimentCounts BOTH_BRANCHES = AggregatedExperimentCounts.builder()
            .aggregated(1)
            .notAggregated(1)
            .build();

    public boolean hasAggregated() {
        return aggregated == 0 && notAggregated == 0 || aggregated > 0;
    }

    public boolean hasRaw() {
        return aggregated == 0 && notAggregated == 0 || notAggregated > 0;
    }
}
