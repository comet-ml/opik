package com.comet.opik.domain.experiments.aggregations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Aggregated Experiment Counts")
class AggregatedExperimentCountsTest {

    @Test
    @DisplayName("a zero non-aggregated count drops the raw branch while keeping the aggregated one")
    void zeroNonAggregatedDropsRawBranch() {
        var counts = AggregatedExperimentCounts.builder()
                .aggregated(7546)
                .notAggregated(0)
                .build();

        assertThat(counts.hasRaw())
                .as("no experiment needs the raw fallback, so its branch must not be rendered")
                .isFalse();
        assertThat(counts.hasAggregated()).isTrue();
    }

    @Test
    @DisplayName("a single non-aggregated experiment keeps the raw branch")
    void anyNonAggregatedKeepsRawBranch() {
        var counts = AggregatedExperimentCounts.builder()
                .aggregated(56)
                .notAggregated(3)
                .build();

        assertThat(counts.hasRaw())
                .as("dropping the branch here would omit the three non-aggregated experiments")
                .isTrue();
        assertThat(counts.hasAggregated()).isTrue();
    }

    @Test
    @DisplayName("a zero aggregated count drops the aggregated branch while keeping the raw one")
    void zeroAggregatedDropsAggregatedBranch() {
        var counts = AggregatedExperimentCounts.builder()
                .aggregated(0)
                .notAggregated(12)
                .build();

        assertThat(counts.hasAggregated()).isFalse();
        assertThat(counts.hasRaw()).isTrue();
    }

    @Test
    @DisplayName("both counts zero keeps both branches rather than rendering an empty query")
    void bothCountsZeroKeepsBothBranches() {
        var counts = AggregatedExperimentCounts.builder()
                .aggregated(0)
                .notAggregated(0)
                .build();

        assertThat(counts.hasAggregated()).isTrue();
        assertThat(counts.hasRaw()).isTrue();
    }

    @Test
    @DisplayName("the both-branches fallback keeps both branches")
    void bothBranchesFallbackKeepsBothBranches() {
        assertThat(AggregatedExperimentCounts.BOTH_BRANCHES.hasAggregated()).isTrue();
        assertThat(AggregatedExperimentCounts.BOTH_BRANCHES.hasRaw()).isTrue();
    }
}
