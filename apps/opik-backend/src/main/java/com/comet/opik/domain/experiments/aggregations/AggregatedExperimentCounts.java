package com.comet.opik.domain.experiments.aggregations;

public record AggregatedExperimentCounts(long aggregated, long notAggregated) {
    public static final AggregatedExperimentCounts BOTH_BRANCHES = new AggregatedExperimentCounts(1, 1);

    public boolean hasAggregated() {
        return aggregated == 0 && notAggregated == 0 || aggregated > 0;
    }

    public boolean hasRaw() {
        return aggregated == 0 && notAggregated == 0 || notAggregated > 0;
    }
}
