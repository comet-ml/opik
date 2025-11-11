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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
            List<ExperimentGroupAggregationItem> groupItems) {
        var contentMap = new HashMap<String, GroupContentWithAggregations>();

        for (ExperimentGroupAggregationItem item : groupItems) {
            buildNestedGroupsWithAggregations(contentMap, item, 0);
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
            ExperimentGroupAggregationItem item, int depth) {
        if (depth >= item.groupValues().size()) {
            return;
        }

        String groupingValue = item.groupValues().get(depth);
        if (groupingValue == null) {
            return;
        }

        GroupContentWithAggregations currentLevel = parentLevel.computeIfAbsent(
                groupingValue,
                key -> {
                    // For leaf nodes (last level), include actual aggregation data
                    if (depth == item.groupValues().size() - 1) {
                        return GroupContentWithAggregations.builder()
                                .aggregations(buildAggregationData(item))
                                .groups(Map.of())
                                .build();
                    } else {
                        // For intermediate nodes, initialize with empty aggregations
                        return GroupContentWithAggregations.builder()
                                .aggregations(AggregationData.builder()
                                        .experimentCount(0L)
                                        .traceCount(0L)
                                        .totalEstimatedCost(BigDecimal.ZERO)
                                        .totalEstimatedCostAvg(BigDecimal.ZERO)
                                        .duration(null)
                                        .feedbackScores(List.of())
                                        .build())
                                .groups(new HashMap<>())
                                .build();
                    }
                });

        // Recursively build nested groups
        buildNestedGroupsWithAggregations(currentLevel.groups(), item, depth + 1);
    }

    public GroupContentWithAggregations calculateRecursiveAggregations(GroupContentWithAggregations content) {
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

            // For feedback scores (weighted average per name)
            if (childAgg.feedbackScores() != null) {
                for (FeedbackScoreAverage score : childAgg.feedbackScores()) {
                    String name = score.name();
                    BigDecimal value = score.value();

                    if (value != null && name != null) {
                        feedbackScoreSums.merge(name, value.multiply(BigDecimal.valueOf(expCount)), BigDecimal::add);
                        feedbackScoreCounts.merge(name, expCount, Long::sum);
                    }
                }
            }
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

        // Build updated aggregation data
        return AggregationData.builder()
                .experimentCount(totalExperimentCount)
                .traceCount(totalTraceCount)
                .totalEstimatedCost(totalCost)
                .totalEstimatedCostAvg(avgCost)
                .duration(avgDuration)
                .feedbackScores(avgFeedbackScores)
                .build();
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
                                        .sorted((a, b) -> b.lastCreatedExperimentAt()
                                                .compareTo(a.lastCreatedExperimentAt()))
                                        .map(ExperimentGroupWithTime::name)
                                        .distinct()
                                        .toList())
                        .build())
                .toList();
    }
}
