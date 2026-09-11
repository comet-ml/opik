package com.comet.opik.domain;

import com.comet.opik.api.AggregationData;
import com.comet.opik.api.GroupContentWithAggregations;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentResponseBuilderTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final ExperimentResponseBuilder builder = new ExperimentResponseBuilder();

    private AggregationData randomAggregation(BigDecimal passRateAvg, Long passedCountSum, Long totalCountSum) {
        return AggregationData.builder()
                .experimentCount(ThreadLocalRandom.current().nextLong(1, 100))
                .traceCount(ThreadLocalRandom.current().nextLong(1, 1000))
                .totalEstimatedCost(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0, 100)))
                .totalEstimatedCostAvg(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0, 50)))
                .feedbackScores(List.of())
                .experimentScores(List.of())
                .passRateAvg(passRateAvg)
                .passedCountSum(passedCountSum)
                .totalCountSum(totalCountSum)
                .build();
    }

    @Test
    @DisplayName("calculateRecursiveAggregations aggregates pass rate from children using weighted average")
    void calculateRecursiveAggregations__passRate__weightedAverage() {
        var random = ThreadLocalRandom.current();

        long expCount1 = random.nextLong(1, 50);
        long passed1 = random.nextLong(1, 20);
        long total1 = passed1 + random.nextLong(0, 10);
        var passRate1 = BigDecimal.valueOf(random.nextDouble(0.1, 1.0));

        long expCount2 = random.nextLong(1, 50);
        long passed2 = random.nextLong(1, 20);
        long total2 = passed2 + random.nextLong(0, 10);
        var passRate2 = BigDecimal.valueOf(random.nextDouble(0.1, 1.0));

        var child1Agg = randomAggregation(passRate1, passed1, total1).toBuilder()
                .experimentCount(expCount1).build();
        var child2Agg = randomAggregation(passRate2, passed2, total2).toBuilder()
                .experimentCount(expCount2).build();

        var child1 = GroupContentWithAggregations.builder()
                .label(podamFactory.manufacturePojo(String.class))
                .aggregations(child1Agg)
                .groups(Map.of())
                .build();

        var child2 = GroupContentWithAggregations.builder()
                .label(podamFactory.manufacturePojo(String.class))
                .aggregations(child2Agg)
                .groups(Map.of())
                .build();

        var childGroups = new HashMap<String, GroupContentWithAggregations>();
        childGroups.put(podamFactory.manufacturePojo(String.class), child1);
        childGroups.put(podamFactory.manufacturePojo(String.class), child2);

        var parent = GroupContentWithAggregations.builder()
                .label(podamFactory.manufacturePojo(String.class))
                .aggregations(randomAggregation(null, null, null).toBuilder()
                        .experimentCount(0L).build())
                .groups(childGroups)
                .build();

        var result = builder.calculateRecursiveAggregations(parent);

        var expectedPassRate = passRate1.multiply(BigDecimal.valueOf(expCount1))
                .add(passRate2.multiply(BigDecimal.valueOf(expCount2)))
                .divide(BigDecimal.valueOf(expCount1 + expCount2), 9, RoundingMode.HALF_UP);

        var weightedCostAvg = child1Agg.totalEstimatedCostAvg().multiply(BigDecimal.valueOf(expCount1))
                .add(child2Agg.totalEstimatedCostAvg().multiply(BigDecimal.valueOf(expCount2)))
                .divide(BigDecimal.valueOf(expCount1 + expCount2), 9, RoundingMode.HALF_UP);

        var expectedAggregation = AggregationData.builder()
                .experimentCount(expCount1 + expCount2)
                .traceCount(child1Agg.traceCount() + child2Agg.traceCount())
                .totalEstimatedCost(child1Agg.totalEstimatedCost().add(child2Agg.totalEstimatedCost()))
                .totalEstimatedCostAvg(weightedCostAvg)
                .duration(null)
                .feedbackScores(List.of())
                .experimentScores(List.of())
                .passRateAvg(expectedPassRate)
                .passedCountSum(passed1 + passed2)
                .totalCountSum(total1 + total2)
                .build();

        assertThat(result.aggregations())
                .usingRecursiveComparison()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(expectedAggregation);
    }

    @Test
    @DisplayName("calculateRecursiveAggregations returns null pass rate when no children have pass rate")
    void calculateRecursiveAggregations__noPassRate__returnsNull() {
        var child = GroupContentWithAggregations.builder()
                .label(podamFactory.manufacturePojo(String.class))
                .aggregations(randomAggregation(null, null, null))
                .groups(Map.of())
                .build();

        var childGroups = new HashMap<String, GroupContentWithAggregations>();
        childGroups.put(podamFactory.manufacturePojo(String.class), child);

        var parent = GroupContentWithAggregations.builder()
                .label(podamFactory.manufacturePojo(String.class))
                .aggregations(randomAggregation(null, null, null).toBuilder()
                        .experimentCount(0L).build())
                .groups(childGroups)
                .build();

        var result = builder.calculateRecursiveAggregations(parent);

        var expectedAggregation = AggregationData.builder()
                .experimentCount(child.aggregations().experimentCount())
                .traceCount(child.aggregations().traceCount())
                .totalEstimatedCost(child.aggregations().totalEstimatedCost())
                .totalEstimatedCostAvg(child.aggregations().totalEstimatedCostAvg())
                .duration(null)
                .feedbackScores(List.of())
                .experimentScores(List.of())
                .passRateAvg(null)
                .passedCountSum(null)
                .totalCountSum(null)
                .build();

        assertThat(result.aggregations())
                .usingRecursiveComparison()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(expectedAggregation);
    }

    @Test
    @DisplayName("calculateRecursiveAggregations preserves leaf node pass rate as-is")
    void calculateRecursiveAggregations__leafNode__preservedAsIs() {
        var passRate = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0.1, 1.0));
        long passed = ThreadLocalRandom.current().nextLong(1, 20);
        long total = passed + ThreadLocalRandom.current().nextLong(0, 10);

        var leaf = GroupContentWithAggregations.builder()
                .label(podamFactory.manufacturePojo(String.class))
                .aggregations(randomAggregation(passRate, passed, total))
                .groups(Map.of())
                .build();

        var result = builder.calculateRecursiveAggregations(leaf);

        assertThat(result.aggregations())
                .usingRecursiveComparison()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(leaf.aggregations());
    }
}
