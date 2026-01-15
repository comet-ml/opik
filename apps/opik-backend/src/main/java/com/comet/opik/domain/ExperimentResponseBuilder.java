package com.comet.opik.domain;

import com.comet.opik.api.AggregationData;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupAggregationsResponse;
import com.comet.opik.api.ExperimentGroupEnrichInfoHolder;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.ExperimentGroupResponse;
import com.comet.opik.api.ExperimentGroupWithTime;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.GroupContentWithAggregations;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.grouping.GroupBy;
import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.api.grouping.GroupingFactory.DATASET_ID;

public class ExperimentResponseBuilder {

    public static final String DELETED_DATASET = "__DELETED";

    public ExperimentGroupResponse buildGroupResponse(List<ExperimentGroupItem> groupItems,
            ExperimentGroupEnrichInfoHolder enrichInfoHolder, List<GroupBy> groups) {
        var contentMap = new HashMap<String, ExperimentGroupResponse.GroupContent>();

        for (ExperimentGroupItem item : groupItems) {
            buildNestedGroups(contentMap, item.groupValues(), 0, enrichInfoHolder, groups);
        }

        return ExperimentGroupResponse.builder()
                .content(contentMap)
                .details(ExperimentGroupResponse.GroupDetails.builder()
                        .groupsDetails(buildSortedGroups(groupItems, enrichInfoHolder, groups))
                        .build())
                .build();
    }

    public ExperimentGroupAggregationsResponse buildGroupAggregationsResponse(
            List<ExperimentGroupAggregationItem> groupItems,
            @NonNull ExperimentGroupEnrichInfoHolder enrichInfoHolder,
            List<GroupBy> groups) {
        var contentMap = new HashMap<String, GroupContentWithAggregations>();

        if (CollectionUtils.isEmpty(groupItems) || CollectionUtils.isEmpty(groups)) {
            return ExperimentGroupAggregationsResponse.builder()
                    .content(contentMap)
                    .build();
        }

        for (ExperimentGroupAggregationItem item : groupItems) {
            buildNestedGroupsWithAggregations(contentMap, item, 0, enrichInfoHolder, groups);
        }

        // Calculate recursive aggregations for parent levels
        var updatedContentMap = new HashMap<String, GroupContentWithAggregations>();
        for (Map.Entry<String, GroupContentWithAggregations> entry : contentMap.entrySet()) {
            updatedContentMap.put(entry.getKey(), calculateRecursiveAggregations(entry.getValue()));
        }

        return ExperimentGroupAggregationsResponse.builder()
                .content(updatedContentMap)
                .build();
    }

    private void buildNestedGroupsWithAggregations(Map<String, GroupContentWithAggregations> parentLevel,
            ExperimentGroupAggregationItem item, int depth,
            ExperimentGroupEnrichInfoHolder enrichInfoHolder, List<GroupBy> groups) {
        if (depth >= item.groupValues().size()) {
            return;
        }

        String groupingValue = item.groupValues().get(depth);
        if (groupingValue == null) {
            return;
        }

        GroupBy currentGroup = groups.get(depth);
        String label = resolveLabel(groupingValue, currentGroup, enrichInfoHolder);

        GroupContentWithAggregations currentLevel = parentLevel.computeIfAbsent(
                groupingValue,
                key -> {
                    // For leaf nodes (last level), include actual aggregation data
                    if (depth == item.groupValues().size() - 1) {
                        return GroupContentWithAggregations.builder()
                                .label(label)
                                .aggregations(buildAggregationData(item))
                                .groups(Map.of())
                                .build();
                    } else {
                        // For intermediate nodes, initialize with empty aggregations
                        return GroupContentWithAggregations.builder()
                                .label(label)
                                .aggregations(AggregationData.builder()
                                        .experimentCount(0L)
                                        .traceCount(0L)
                                        .totalEstimatedCost(BigDecimal.ZERO)
                                        .totalEstimatedCostAvg(BigDecimal.ZERO)
                                        .duration(null)
                                        .feedbackScores(List.of())
                                        .experimentScores(List.of())
                                        .build())
                                .groups(new HashMap<>())
                                .build();
                    }
                });

        // Recursively build nested groups
        buildNestedGroupsWithAggregations(currentLevel.groups(), item, depth + 1, enrichInfoHolder, groups);
    }

    private String resolveLabel(String groupingValue, GroupBy group,
            ExperimentGroupEnrichInfoHolder enrichInfoHolder) {
        return switch (group.field()) {
            case DATASET_ID -> {
                Map<UUID, Dataset> datasetMap = enrichInfoHolder.datasetMap();
                if (datasetMap == null) {
                    yield DELETED_DATASET;
                }
                yield Optional.ofNullable(datasetMap.get(UUID.fromString(groupingValue)))
                        .map(Dataset::name)
                        .orElse(DELETED_DATASET);
            }
            default -> groupingValue;
        };
    }

    public GroupContentWithAggregations calculateRecursiveAggregations(@NonNull GroupContentWithAggregations content) {
        if (content.groups().isEmpty()) {
            // Leaf node - return as-is
            return content;
        }

        // Recursively calculate aggregations for all child groups first
        var updatedChildGroups = new HashMap<String, GroupContentWithAggregations>();
        for (Map.Entry<String, GroupContentWithAggregations> entry : content.groups().entrySet()) {
            updatedChildGroups.put(entry.getKey(), calculateRecursiveAggregations(entry.getValue()));
        }

        // Return new GroupContentWithAggregations with calculated aggregations
        return GroupContentWithAggregations.builder()
                .label(content.label())
                .aggregations(calculateAggregatedChildrenValues(updatedChildGroups))
                .groups(updatedChildGroups)
                .build();
    }

    private AggregationData calculateAggregatedChildrenValues(
            HashMap<String, GroupContentWithAggregations> childGroups) {
        // Calculate aggregated values from all children
        long totalExperimentCount = 0;
        long totalTraceCount = 0;
        BigDecimal totalCost = BigDecimal.ZERO;

        // For weighted averages
        BigDecimal weightedCostSum = BigDecimal.ZERO;
        BigDecimal p50Sum = BigDecimal.ZERO;
        BigDecimal p90Sum = BigDecimal.ZERO;
        BigDecimal p99Sum = BigDecimal.ZERO;

        // For feedback scores - group by name and calculate weighted averages
        Map<String, BigDecimal> feedbackScoreSums = new HashMap<>();
        Map<String, Long> feedbackScoreCounts = new HashMap<>();

        // For experiment scores - group by name and calculate weighted averages
        Map<String, BigDecimal> experimentScoreSums = new HashMap<>();
        Map<String, Long> experimentScoreCounts = new HashMap<>();

        for (GroupContentWithAggregations child : childGroups.values()) {
            AggregationData childAgg = child.aggregations();
            long expCount = childAgg.experimentCount();

            totalExperimentCount += childAgg.experimentCount();
            totalTraceCount += childAgg.traceCount();
            if (childAgg.totalEstimatedCost() != null) {
                totalCost = totalCost.add(childAgg.totalEstimatedCost());
            }

            // For weighted cost average
            if (childAgg.totalEstimatedCostAvg() != null) {
                weightedCostSum = weightedCostSum.add(
                        childAgg.totalEstimatedCostAvg().multiply(BigDecimal.valueOf(expCount)));
            }

            // For duration percentiles (weighted average)
            if (childAgg.duration() != null) {
                if (childAgg.duration().p50() != null) {
                    p50Sum = p50Sum.add(childAgg.duration().p50().multiply(BigDecimal.valueOf(expCount)));
                }
                if (childAgg.duration().p90() != null) {
                    p90Sum = p90Sum.add(childAgg.duration().p90().multiply(BigDecimal.valueOf(expCount)));
                }
                if (childAgg.duration().p99() != null) {
                    p99Sum = p99Sum.add(childAgg.duration().p99().multiply(BigDecimal.valueOf(expCount)));
                }
            }

            // Accumulate feedback scores
            accumulateScores(childAgg.feedbackScores(), expCount, feedbackScoreSums, feedbackScoreCounts);

            // Accumulate experiment scores
            accumulateScores(childAgg.experimentScores(), expCount, experimentScoreSums, experimentScoreCounts);
        }

        // Calculate averages
        BigDecimal avgCost = totalExperimentCount > 0
                ? weightedCostSum.divide(BigDecimal.valueOf(totalExperimentCount), 9, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        PercentageValues avgDuration = totalExperimentCount > 0
                ? new PercentageValues(
                        p50Sum.divide(BigDecimal.valueOf(totalExperimentCount), 9, RoundingMode.HALF_UP),
                        p90Sum.divide(BigDecimal.valueOf(totalExperimentCount), 9, RoundingMode.HALF_UP),
                        p99Sum.divide(BigDecimal.valueOf(totalExperimentCount), 9, RoundingMode.HALF_UP))
                : null;

        List<FeedbackScoreAverage> avgFeedbackScores = buildAvgFeedbackScores(feedbackScoreSums, feedbackScoreCounts);
        List<FeedbackScoreAverage> avgExperimentScores = buildAvgFeedbackScores(experimentScoreSums,
                experimentScoreCounts);

        // Build updated aggregation data
        return AggregationData.builder()
                .experimentCount(totalExperimentCount)
                .traceCount(totalTraceCount)
                .totalEstimatedCost(totalCost)
                .totalEstimatedCostAvg(avgCost)
                .duration(avgDuration)
                .feedbackScores(avgFeedbackScores)
                .experimentScores(avgExperimentScores)
                .build();
    }

    /**
     * Accumulate scores for weighted average calculation.
     *
     * @param scores List of scores to accumulate
     * @param experimentCount Number of experiments contributing to these scores
     * @param scoreSums Map to accumulate weighted sums
     * @param scoreCounts Map to track total experiment counts per score name
     */
    private void accumulateScores(
            List<FeedbackScoreAverage> scores,
            long experimentCount,
            Map<String, BigDecimal> scoreSums,
            Map<String, Long> scoreCounts) {

        if (scores == null) {
            return;
        }

        for (FeedbackScoreAverage score : scores) {
            String name = score.name();
            BigDecimal value = score.value();

            if (value != null && name != null) {
                scoreSums.merge(name, value.multiply(BigDecimal.valueOf(experimentCount)), BigDecimal::add);
                scoreCounts.merge(name, experimentCount, Long::sum);
            }
        }
    }

    private List<FeedbackScoreAverage> buildAvgFeedbackScores(Map<String, BigDecimal> feedbackScoreSums,
            Map<String, Long> feedbackScoreCounts) {
        return feedbackScoreSums.entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    BigDecimal sum = entry.getValue();
                    Long count = feedbackScoreCounts.get(name);
                    BigDecimal avg = count > 0
                            ? sum.divide(BigDecimal.valueOf(count), 9, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new FeedbackScoreAverage(name, avg);
                })
                .toList();
    }

    private AggregationData buildAggregationData(ExperimentGroupAggregationItem item) {
        return AggregationData.builder()
                .experimentCount(item.experimentCount())
                .traceCount(item.traceCount())
                .totalEstimatedCost(item.totalEstimatedCost())
                .totalEstimatedCostAvg(item.totalEstimatedCostAvg())
                .duration(item.duration())
                .feedbackScores(item.feedbackScores())
                .experimentScores(item.experimentScores())
                .build();
    }

    private void buildNestedGroups(Map<String, ExperimentGroupResponse.GroupContent> parentLevel,
            List<String> groupValues, int depth, ExperimentGroupEnrichInfoHolder enrichInfoHolder,
            List<GroupBy> groups) {
        if (depth >= groupValues.size()) {
            return;
        }

        String groupingValue = groupValues.get(depth);
        if (groupingValue == null) {
            return;
        }

        ExperimentGroupResponse.GroupContent currentLevel = parentLevel.computeIfAbsent(
                groupingValue,
                // We have to enrich with the dataset name if it's for dataset
                key -> buildGroupNode(
                        key,
                        enrichInfoHolder,
                        groups.get(depth)));

        // Recursively build nested groups
        buildNestedGroups(currentLevel.groups(), groupValues, depth + 1, enrichInfoHolder, groups);
    }

    private ExperimentGroupResponse.GroupContent buildGroupNode(String groupingValue,
            ExperimentGroupEnrichInfoHolder enrichInfoHolder, GroupBy group) {
        return switch (group.field()) {
            case DATASET_ID ->
                ExperimentGroupResponse.GroupContent.builder()
                        .label(Optional.ofNullable(enrichInfoHolder.datasetMap().get(UUID.fromString(groupingValue)))
                                .map(Dataset::name)
                                .orElse(DELETED_DATASET))
                        .groups(new HashMap<>())
                        .build();

            default -> ExperimentGroupResponse.GroupContent.builder()
                    .groups(new HashMap<>())
                    .build();
        };
    }

    private List<ExperimentGroupResponse.GroupDetail> buildSortedGroups(List<ExperimentGroupItem> groupItems,
            ExperimentGroupEnrichInfoHolder enrichInfoHolder, List<GroupBy> groups) {
        List<ArrayList<ExperimentGroupWithTime>> groupsWithTime = IntStream.range(0, groups.size())
                .mapToObj(i -> new ArrayList<ExperimentGroupWithTime>())
                .toList();

        groupItems.forEach(item -> {
            for (int i = 0; i < item.groupValues().size(); i++) {
                String groupingValue = item.groupValues().get(i);
                if (groupingValue != null) {
                    String label = switch (groups.get(i).field()) {
                        case DATASET_ID ->
                            Optional.ofNullable(enrichInfoHolder.datasetMap().get(UUID.fromString(groupingValue)))
                                    .map(Dataset::name)
                                    .orElse(DELETED_DATASET);
                        default -> groupingValue;
                    };
                    groupsWithTime.get(i).add(
                            new ExperimentGroupWithTime(
                                    label,
                                    item.lastCreatedExperimentAt()));
                }
            }
        });

        return groupsWithTime.stream()
                .map(groupList -> ExperimentGroupResponse.GroupDetail.builder()
                        .groupSorting(
                                groupList.stream()
                                        .sorted(
                                                Comparator.comparing(
                                                        ExperimentGroupWithTime::lastCreatedExperimentAt).reversed()
                                                        .thenComparing(ExperimentGroupWithTime::name))
                                        .map(ExperimentGroupWithTime::name)
                                        .distinct()
                                        .toList())
                        .build())
                .toList();
    }
}
