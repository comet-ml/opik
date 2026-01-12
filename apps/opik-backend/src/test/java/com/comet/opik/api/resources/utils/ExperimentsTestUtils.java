package com.comet.opik.api.resources.utils;

import com.comet.opik.api.AggregationData;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupAggregationsResponse;
import com.comet.opik.api.ExperimentGroupEnrichInfoHolder;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.ExperimentGroupResponse;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.domain.ExperimentResponseBuilder;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.ValidationUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.grouping.GroupingFactory.DATASET_ID;
import static com.comet.opik.api.grouping.GroupingFactory.METADATA;
import static com.comet.opik.api.grouping.GroupingFactory.TAGS;

@UtilityClass
public class ExperimentsTestUtils {

    private static final ExperimentResponseBuilder experimentResponseBuilder = new ExperimentResponseBuilder();

    /**
     * Helper function to build expected ExperimentGroupResponse for testing.
     * This function groups experiments according to the provided GroupBy criteria and
     * builds the nested response structure similar to how ExperimentService does it.
     */
    public static ExperimentGroupResponse buildExpectedGroupResponse(List<GroupBy> groups,
            List<Experiment> experiments) {
        // Explode experiments by LIST fields (like arrayJoin in ClickHouse)
        // Each experiment with LIST fields will appear in multiple groups
        List<ExperimentWithGroupValues> explodedExperiments = explodeExperimentsByListFields(experiments, groups);

        // Group experiments by extracting values for each GroupBy criterion
        Map<List<String>, List<Experiment>> experimentGroups = explodedExperiments.stream()
                .collect(Collectors.groupingBy(
                        ExperimentWithGroupValues::groupValues,
                        Collectors.mapping(ExperimentWithGroupValues::experiment, Collectors.toList())));

        // Convert to ExperimentGroupItem format (similar to what comes from a database)
        // Include lastCreatedExperimentAt for each group
        List<ExperimentGroupItem> groupItems = experimentGroups.entrySet().stream()
                .map(entry -> {
                    var lastCreatedAt = entry.getValue().stream()
                            .map(Experiment::createdAt)
                            .filter(Objects::nonNull)
                            .max(Instant::compareTo)
                            .orElse(null);
                    return ExperimentGroupItem.builder()
                            .groupValues(entry.getKey())
                            .lastCreatedExperimentAt(lastCreatedAt)
                            .build();
                })
                .toList();

        // Build enrichment info (dataset mapping)
        Map<UUID, Dataset> datasetMap = getDatasetMapFromExperiments(experiments);
        var enrichInfoHolder = ExperimentGroupEnrichInfoHolder.builder()
                .datasetMap(datasetMap)
                .build();

        // Build the nested response structure using the production builder
        // This will automatically compute groupsSorting
        return experimentResponseBuilder.buildGroupResponse(groupItems, enrichInfoHolder, groups);
    }

    /**
     * Build expected ExperimentGroupAggregationsResponse for testing.
     * This function groups experiments and calculates aggregations based on the provided criteria.
     * Uses the actual production ExperimentResponseBuilder class to ensure consistency between test and production code.
     */
    public static ExperimentGroupAggregationsResponse buildExpectedGroupAggregationsResponse(
            List<GroupBy> groups,
            List<Experiment> experiments,
            Map<UUID, List<ExperimentItem>> experimentToItems,
            Map<UUID, List<Span>> traceToSpans,
            List<Trace> traces) {

        // Explode experiments by LIST fields (like arrayJoin in ClickHouse)
        // Each experiment with LIST fields will appear in multiple groups
        List<ExperimentWithGroupValues> explodedExperiments = explodeExperimentsByListFields(experiments, groups);

        // Group experiments by extracting values for each GroupBy criterion
        Map<List<String>, List<Experiment>> experimentGroups = explodedExperiments.stream()
                .collect(Collectors.groupingBy(
                        ExperimentWithGroupValues::groupValues,
                        Collectors.mapping(ExperimentWithGroupValues::experiment, Collectors.toList())));

        // Convert to ExperimentGroupAggregationItem format (similar to what comes from a database)
        List<ExperimentGroupAggregationItem> groupItems = experimentGroups.entrySet().stream()
                .map(entry -> {
                    AggregationData aggregations = calculateAggregations(
                            entry.getValue(), experimentToItems, traceToSpans, traces);
                    return ExperimentGroupAggregationItem.builder()
                            .groupValues(entry.getKey())
                            .experimentCount(aggregations.experimentCount())
                            .traceCount(aggregations.traceCount())
                            .totalEstimatedCost(aggregations.totalEstimatedCost())
                            .totalEstimatedCostAvg(aggregations.totalEstimatedCostAvg())
                            .duration(aggregations.duration())
                            .feedbackScores(aggregations.feedbackScores())
                            .experimentScores(aggregations.experimentScores())
                            .build();
                })
                .toList();

        // Build enrichment info (dataset mapping)
        Map<UUID, Dataset> datasetMap = getDatasetMapFromExperiments(experiments);
        var enrichInfoHolder = ExperimentGroupEnrichInfoHolder.builder()
                .datasetMap(datasetMap)
                .build();

        // Build the nested response structure using the production builder
        return experimentResponseBuilder.buildGroupAggregationsResponse(groupItems, enrichInfoHolder, groups);
    }

    public static List<BigDecimal> getQuantities(Stream<Trace> traces) {
        return StatsUtils.calculateQuantiles(
                traces.filter(entity -> entity.endTime() != null)
                        .map(entity -> entity.startTime().until(entity.endTime(), ChronoUnit.MICROS))
                        .map(duration -> duration / 1_000.0)
                        .toList(),
                List.of(0.50, 0.90, 0.99));
    }

    /**
     * Calculate aggregations for a group of experiments.
     * First calculates avgCost and duration percentiles per experiment,
     * then averages those values across all experiments in the group.
     */
    private AggregationData calculateAggregations(
            List<Experiment> experiments,
            Map<UUID, List<ExperimentItem>> experimentToItems,
            Map<UUID, List<Span>> traceToSpans,
            List<Trace> traces) {

        long experimentCount = experiments.size();

        // Calculate per-experiment metrics
        List<ExperimentMetrics> experimentMetrics = experiments.stream()
                .map(experiment -> calculateExperimentMetrics(experiment, experimentToItems, traceToSpans, traces))
                .toList();

        // Calculate total trace count across all experiments
        long totalTraceCount = experimentMetrics.stream()
                .mapToLong(ExperimentMetrics::traceCount)
                .sum();

        // Calculate total cost across all experiments
        BigDecimal totalCost = experimentMetrics.stream()
                .map(ExperimentMetrics::totalCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Average the per-experiment avgCost values
        BigDecimal avgCost = experimentMetrics.stream()
                .map(ExperimentMetrics::avgCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(experimentCount), ValidationUtils.SCALE, RoundingMode.HALF_UP);

        // Average the per-experiment duration percentiles
        PercentageValues avgDurationPercentiles = averageDurationPercentiles(experimentMetrics);

        // Calculate feedback score averages across all experiments
        var allItems = experiments.stream()
                .flatMap(exp -> experimentToItems.getOrDefault(exp.id(), List.of()).stream())
                .toList();
        List<FeedbackScoreAverage> feedbackScores = calculateFeedbackScoreAverages(allItems);

        // Calculate experiment score averages across all experiments
        List<FeedbackScoreAverage> experimentScores = calculateExperimentScoreAverages(experiments);

        return AggregationData.builder()
                .experimentCount(experimentCount)
                .traceCount(totalTraceCount)
                .totalEstimatedCost(totalCost)
                .totalEstimatedCostAvg(avgCost)
                .duration(avgDurationPercentiles)
                .feedbackScores(feedbackScores)
                .experimentScores(experimentScores)
                .build();
    }

    /**
     * Calculate metrics for a single experiment.
     */
    private ExperimentMetrics calculateExperimentMetrics(
            Experiment experiment,
            Map<UUID, List<ExperimentItem>> experimentToItems,
            Map<UUID, List<Span>> traceToSpans,
            List<Trace> traces) {

        // Get experiment items for this experiment
        var experimentItems = experimentToItems.getOrDefault(experiment.id(), List.of());

        // Get traces for this experiment
        var experimentTraces = experimentItems.stream()
                .map(ExperimentItem::traceId)
                .distinct()
                .map(traceId -> traces.stream()
                        .filter(t -> t.id().equals(traceId))
                        .findFirst()
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();

        // Get spans for this experiment's traces
        var experimentSpans = experimentTraces.stream()
                .flatMap(trace -> traceToSpans.getOrDefault(trace.id(), List.of()).stream())
                .toList();

        // Calculate total cost for this experiment
        BigDecimal totalCost = experimentSpans.stream()
                .map(Span::totalEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate average cost for this experiment (total cost / trace count)
        long traceCount = experimentTraces.size();
        BigDecimal avgCost = traceCount > 0
                ? totalCost.divide(BigDecimal.valueOf(traceCount), ValidationUtils.SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Calculate duration percentiles for this experiment using the same logic as FindExperiments
        List<BigDecimal> quantiles = getQuantities(experimentTraces.stream());
        PercentageValues durationPercentiles = quantiles.isEmpty()
                ? new PercentageValues(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                : new PercentageValues(quantiles.get(0), quantiles.get(1), quantiles.get(2));

        return new ExperimentMetrics(traceCount, totalCost, avgCost, durationPercentiles);
    }

    /**
     * Average the duration percentiles across all experiments.
     */
    private PercentageValues averageDurationPercentiles(List<ExperimentMetrics> experimentMetrics) {
        List<PercentageValues> allPercentiles = experimentMetrics.stream()
                .map(ExperimentMetrics::durationPercentiles)
                .filter(Objects::nonNull)
                .toList();

        if (allPercentiles.isEmpty()) {
            return new PercentageValues(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal avgP50 = allPercentiles.stream()
                .map(PercentageValues::p50)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(allPercentiles.size()), ValidationUtils.SCALE, RoundingMode.HALF_UP);

        BigDecimal avgP90 = allPercentiles.stream()
                .map(PercentageValues::p90)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(allPercentiles.size()), ValidationUtils.SCALE, RoundingMode.HALF_UP);

        BigDecimal avgP99 = allPercentiles.stream()
                .map(PercentageValues::p99)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(allPercentiles.size()), ValidationUtils.SCALE, RoundingMode.HALF_UP);

        return new PercentageValues(avgP50, avgP90, avgP99);
    }

    /**
     * Generic method to calculate score averages from a list of score objects.
     *
     * @param items List of items to extract scores from
     * @param scoreListExtractor Function to extract list of score objects from each item
     * @param nameExtractor Function to extract name from a score object
     * @param valueExtractor Function to extract value from a score object
     * @param <T> Type of items to process
     * @param <S> Type of score objects
     * @return List of FeedbackScoreAverage with calculated averages
     */
    private <T, S> List<FeedbackScoreAverage> calculateScoreAverages(
            List<T> items,
            Function<T, List<S>> scoreListExtractor,
            Function<S, String> nameExtractor,
            Function<S, BigDecimal> valueExtractor) {

        Map<String, List<BigDecimal>> scoresByName = new HashMap<>();

        for (T item : items) {
            List<S> scores = scoreListExtractor.apply(item);
            if (scores != null) {
                scores.stream()
                        .filter(score -> nameExtractor.apply(score) != null
                                && valueExtractor.apply(score) != null)
                        .forEach(score -> scoresByName
                                .computeIfAbsent(nameExtractor.apply(score), k -> new ArrayList<>())
                                .add(valueExtractor.apply(score)));
            }
        }

        return scoresByName.entrySet().stream()
                .map(entry -> {
                    BigDecimal average = entry.getValue().stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(entry.getValue().size()),
                                    ValidationUtils.SCALE, RoundingMode.HALF_UP);
                    return FeedbackScoreAverage.builder()
                            .name(entry.getKey())
                            .value(average)
                            .build();
                })
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    private List<FeedbackScoreAverage> calculateFeedbackScoreAverages(List<ExperimentItem> items) {
        return calculateScoreAverages(
                items,
                ExperimentItem::feedbackScores,
                FeedbackScore::name,
                FeedbackScore::value);
    }

    /**
     * Calculate experiment score averages across all experiments.
     * Extracts experiment scores from each experiment and calculates the average value for each score name.
     */
    private List<FeedbackScoreAverage> calculateExperimentScoreAverages(List<Experiment> experiments) {
        return calculateScoreAverages(
                experiments,
                Experiment::experimentScores,
                ExperimentScore::name,
                ExperimentScore::value);
    }

    /**
     * Build dataset mapping from experiments for enrichment.
     */
    private Map<UUID, Dataset> getDatasetMapFromExperiments(List<Experiment> experiments) {
        // Extract unique dataset IDs and create mock datasets
        return experiments.stream()
                .filter(exp -> exp.datasetId() != null)
                .collect(Collectors.toMap(
                        Experiment::datasetId,
                        exp -> Dataset.builder()
                                .id(exp.datasetId())
                                .name(exp.datasetName())
                                .build(),
                        (existing, replacement) -> existing // Keep first one in case of duplicates
                ));
    }

    /**
     * Explode experiments by LIST fields (simulating arrayJoin in ClickHouse).
     * For experiments with LIST fields in grouping criteria, create multiple entries
     * (one for each value in the LIST field).
     */
    private static List<ExperimentWithGroupValues> explodeExperimentsByListFields(
            List<Experiment> experiments,
            List<GroupBy> groups) {

        // Check if any grouping field is of type LIST
        boolean hasListField = groups.stream()
                .anyMatch(g -> g.type() == FieldType.LIST);

        if (!hasListField) {
            // No LIST fields, just extract group values normally
            return experiments.stream()
                    .map(exp -> new ExperimentWithGroupValues(exp, extractGroupValues(exp, groups)))
                    .toList();
        }

        // We have LIST fields - need to explode
        List<ExperimentWithGroupValues> result = new ArrayList<>();

        for (Experiment experiment : experiments) {
            // Generate all combinations of group values for this experiment
            List<List<String>> allCombinations = generateGroupValueCombinations(experiment, groups, 0);

            for (List<String> groupValues : allCombinations) {
                result.add(new ExperimentWithGroupValues(experiment, groupValues));
            }
        }

        return result;
    }

    /**
     * Recursively generate all combinations of group values for an experiment,
     * expanding LIST fields into individual values (like arrayJoin).
     */
    private static List<List<String>> generateGroupValueCombinations(
            Experiment experiment,
            List<GroupBy> groups,
            int groupIndex) {

        if (groupIndex >= groups.size()) {
            // Base case: return an empty list
            List<List<String>> result = new ArrayList<>();
            result.add(new ArrayList<>());
            return result;
        }

        GroupBy currentGroup = groups.get(groupIndex);
        List<List<String>> nextCombinations = generateGroupValueCombinations(experiment, groups, groupIndex + 1);
        List<List<String>> result = new ArrayList<>();

        if (currentGroup.type() == FieldType.LIST && currentGroup.field().equals(TAGS)) {
            // LIST field - explode each tag
            var tags = experiment.tags();
            if (tags == null || tags.isEmpty()) {
                // No tags - use empty string
                for (List<String> nextCombo : nextCombinations) {
                    List<String> combo = new ArrayList<>();
                    combo.add("");
                    combo.addAll(nextCombo);
                    result.add(combo);
                }
            } else {
                // Create one combination for each tag
                for (String tag : tags.stream().sorted().toList()) {
                    for (List<String> nextCombo : nextCombinations) {
                        List<String> combo = new ArrayList<>();
                        combo.add(tag);
                        combo.addAll(nextCombo);
                        result.add(combo);
                    }
                }
            }
        } else {
            // Non-LIST field - extract single value
            String value = extractFieldValue(experiment, currentGroup);
            for (List<String> nextCombo : nextCombinations) {
                List<String> combo = new ArrayList<>();
                combo.add(value);
                combo.addAll(nextCombo);
                result.add(combo);
            }
        }

        return result;
    }

    /**
     * Extract grouping values from an experiment based on GroupBy criteria.
     */
    private static List<String> extractGroupValues(Experiment experiment, List<GroupBy> groups) {
        return groups.stream()
                .map(group -> extractFieldValue(experiment, group))
                .toList();
    }

    /**
     * Helper record to hold an experiment with its associated group values.
     * Used when exploding experiments by LIST fields.
     */
    private record ExperimentWithGroupValues(Experiment experiment, List<String> groupValues) {
    }

    /**
     * Extract a single field value from an experiment based on a GroupBy criterion.
     * Note: This should not be called for LIST fields when they are being exploded.
     */
    private static String extractFieldValue(Experiment experiment, GroupBy group) {
        return switch (group.field()) {
            case DATASET_ID -> experiment.datasetId().toString();
            case METADATA -> extractFromJsonMetadata(experiment.metadata(), group.key());
            case TAGS -> throw new IllegalArgumentException(
                    "TAGS field should be handled by explodeExperimentsByListFields, not extractFieldValue");
            default -> throw new IllegalArgumentException("Unsupported grouping field: " + group.field());
        };
    }

    /**
     * Extract value from JSON metadata using JsonPath-like key syntax.
     */
    private static String extractFromJsonMetadata(JsonNode metadata, String key) {
        String jsonText = JsonUtils.getStringOrDefault(metadata);
        try {
            Object value = JsonPath.read(jsonText, key);
            return String.valueOf(value);
        } catch (InvalidPathException e) {
            return "";
        }
    }

    /**
     * Helper record to hold metrics for a single experiment.
     */
    private record ExperimentMetrics(
            long traceCount,
            BigDecimal totalCost,
            BigDecimal avgCost,
            PercentageValues durationPercentiles) {
    }
}
