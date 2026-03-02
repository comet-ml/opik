package com.comet.opik.domain;

import com.comet.opik.api.AggregationData;
import com.comet.opik.api.GroupContentWithAggregations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ExperimentResponseBuilderTest {

    private final ExperimentResponseBuilder builder = new ExperimentResponseBuilder();

    @Test
    @DisplayName("calculateRecursiveAggregations aggregates pass rate from children using weighted average")
    void calculateRecursiveAggregations__passRate__weightedAverage() {
        // Child 1: 3 experiments, pass_rate=0.8, passed=4, total=5
        var child1 = GroupContentWithAggregations.builder()
                .label("child1")
                .aggregations(AggregationData.builder()
                        .experimentCount(3L)
                        .traceCount(10L)
                        .totalEstimatedCost(BigDecimal.ZERO)
                        .totalEstimatedCostAvg(BigDecimal.ZERO)
                        .feedbackScores(List.of())
                        .experimentScores(List.of())
                        .passRateAvg(new BigDecimal("0.8"))
                        .passedCountSum(4L)
                        .totalCountSum(5L)
                        .build())
                .groups(Map.of())
                .build();

        // Child 2: 2 experiments, pass_rate=0.5, passed=2, total=4
        var child2 = GroupContentWithAggregations.builder()
                .label("child2")
                .aggregations(AggregationData.builder()
                        .experimentCount(2L)
                        .traceCount(5L)
                        .totalEstimatedCost(BigDecimal.ZERO)
                        .totalEstimatedCostAvg(BigDecimal.ZERO)
                        .feedbackScores(List.of())
                        .experimentScores(List.of())
                        .passRateAvg(new BigDecimal("0.5"))
                        .passedCountSum(2L)
                        .totalCountSum(4L)
                        .build())
                .groups(Map.of())
                .build();

        var childGroups = new HashMap<String, GroupContentWithAggregations>();
        childGroups.put("c1", child1);
        childGroups.put("c2", child2);

        var parent = GroupContentWithAggregations.builder()
                .label("parent")
                .aggregations(AggregationData.builder()
                        .experimentCount(0L)
                        .traceCount(0L)
                        .totalEstimatedCost(BigDecimal.ZERO)
                        .totalEstimatedCostAvg(BigDecimal.ZERO)
                        .feedbackScores(List.of())
                        .experimentScores(List.of())
                        .build())
                .groups(childGroups)
                .build();

        var result = builder.calculateRecursiveAggregations(parent);

        // Weighted avg: (0.8*3 + 0.5*2) / (3+2) = (2.4+1.0)/5 = 3.4/5 = 0.68
        assertThat(result.aggregations().passRateAvg()).isNotNull();
        assertThat(result.aggregations().passRateAvg().doubleValue()).isCloseTo(0.68, within(0.001));
        assertThat(result.aggregations().passedCountSum()).isEqualTo(6L);
        assertThat(result.aggregations().totalCountSum()).isEqualTo(9L);
        assertThat(result.aggregations().experimentCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("calculateRecursiveAggregations returns null pass rate when no children have pass rate")
    void calculateRecursiveAggregations__noPassRate__returnsNull() {
        var child = GroupContentWithAggregations.builder()
                .label("child")
                .aggregations(AggregationData.builder()
                        .experimentCount(2L)
                        .traceCount(3L)
                        .totalEstimatedCost(BigDecimal.ZERO)
                        .totalEstimatedCostAvg(BigDecimal.ZERO)
                        .feedbackScores(List.of())
                        .experimentScores(List.of())
                        .passRateAvg(null)
                        .passedCountSum(null)
                        .totalCountSum(null)
                        .build())
                .groups(Map.of())
                .build();

        var childGroups = new HashMap<String, GroupContentWithAggregations>();
        childGroups.put("c1", child);

        var parent = GroupContentWithAggregations.builder()
                .label("parent")
                .aggregations(AggregationData.builder()
                        .experimentCount(0L)
                        .traceCount(0L)
                        .totalEstimatedCost(BigDecimal.ZERO)
                        .totalEstimatedCostAvg(BigDecimal.ZERO)
                        .feedbackScores(List.of())
                        .experimentScores(List.of())
                        .build())
                .groups(childGroups)
                .build();

        var result = builder.calculateRecursiveAggregations(parent);

        assertThat(result.aggregations().passRateAvg()).isNull();
        assertThat(result.aggregations().passedCountSum()).isNull();
        assertThat(result.aggregations().totalCountSum()).isNull();
    }

    @Test
    @DisplayName("calculateRecursiveAggregations preserves leaf node pass rate as-is")
    void calculateRecursiveAggregations__leafNode__preservedAsIs() {
        var leaf = GroupContentWithAggregations.builder()
                .label("leaf")
                .aggregations(AggregationData.builder()
                        .experimentCount(1L)
                        .traceCount(5L)
                        .totalEstimatedCost(BigDecimal.TEN)
                        .totalEstimatedCostAvg(BigDecimal.TWO)
                        .feedbackScores(List.of())
                        .experimentScores(List.of())
                        .passRateAvg(new BigDecimal("0.75"))
                        .passedCountSum(3L)
                        .totalCountSum(4L)
                        .build())
                .groups(Map.of())
                .build();

        var result = builder.calculateRecursiveAggregations(leaf);

        assertThat(result.aggregations().passRateAvg()).isEqualByComparingTo(new BigDecimal("0.75"));
        assertThat(result.aggregations().passedCountSum()).isEqualTo(3L);
        assertThat(result.aggregations().totalCountSum()).isEqualTo(4L);
    }
}
