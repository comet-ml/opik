package com.comet.opik.domain.stats;

import com.comet.opik.api.ErrorCountWithDeviation;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import io.r2dbc.spi.Row;
import org.apache.commons.collections4.MapUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.comet.opik.api.ProjectStats.AvgValueStat;
import static com.comet.opik.api.ProjectStats.CountValueStat;
import static com.comet.opik.api.ProjectStats.PercentageValueStat;
import static java.util.stream.Collectors.toMap;

public class StatsMapper {

    public static final String USAGE = "usage";
    public static final String USAGE_SUM = "usage_sum";
    public static final String FEEDBACK_SCORE = "feedback_scores";
    public static final String SPAN_FEEDBACK_SCORE = "span_feedback_scores";
    public static final String TOTAL_ESTIMATED_COST = "total_estimated_cost";
    public static final String TOTAL_ESTIMATED_COST_AVG = "total_estimated_cost_avg";
    public static final String TOTAL_ESTIMATED_COST_SUM = "total_estimated_cost_sum";
    public static final String TOTAL_ESTIMATED_COST_PERCENTILES = "total_estimated_cost_percentiles";
    public static final String FEEDBACK_SCORES_PERCENTILES = "feedback_scores_percentiles";
    public static final String USAGE_TOTAL_TOKENS_PERCENTILES = "usage_total_tokens_percentiles";
    public static final String DURATION = "duration";
    public static final String INPUT = "input";
    public static final String OUTPUT = "output";
    public static final String METADATA = "metadata";
    public static final String TAGS = "tags";
    public static final String LLM_SPAN_COUNT = "llm_span_count";
    public static final String LLM_SPAN_COUNT_AVG = "llm_span_count_avg";
    public static final String SPAN_COUNT = "span_count";
    public static final String SPAN_COUNT_AVG = "span_count_avg";
    public static final String TRACE_COUNT = "trace_count";
    public static final String THREAD_COUNT = "thread_count";
    public static final String GUARDRAILS_FAILED_COUNT = "guardrails_failed_count";
    public static final String RECENT_ERROR_COUNT = "recent_error_count";
    public static final String PAST_PERIOD_ERROR_COUNT = "past_period_error_count";
    public static final String ERROR_COUNT = "error_count";
    public static final String EXPERIMENT_ITEMS_COUNT = "experiment_items_count";

    public static ProjectStats mapProjectStats(Row row, String entityCountLabel) {

        var stats = Stream.<ProjectStats.ProjectStatItem<?>>builder()
                .add(new CountValueStat(entityCountLabel,
                        row.get(entityCountLabel, Long.class)))
                .add(new PercentageValueStat(DURATION, Optional
                        .ofNullable(row.get(DURATION, List.class))
                        .map(durations -> new PercentageValues(
                                getP(durations, 0),
                                getP(durations, 1),
                                getP(durations, 2)))
                        .orElse(null)))
                .add(new CountValueStat(INPUT, row.get(INPUT, Long.class)))
                .add(new CountValueStat(OUTPUT, row.get(OUTPUT, Long.class)))
                .add(new CountValueStat(METADATA, row.get(METADATA, Long.class)))
                .add(new AvgValueStat(TAGS, row.get(TAGS, Double.class)));

        // Add thread count if available (only for project stats)
        if (!entityCountLabel.equals(THREAD_COUNT) && row.getMetadata().contains(THREAD_COUNT)) {
            stats.add(new CountValueStat(THREAD_COUNT, row.get(THREAD_COUNT, Long.class)));
        }

        if (entityCountLabel.equals(TRACE_COUNT)) {
            stats.add(new AvgValueStat(LLM_SPAN_COUNT, row.get(LLM_SPAN_COUNT_AVG, Double.class)));
            stats.add(new AvgValueStat(SPAN_COUNT, row.get(SPAN_COUNT_AVG, Double.class)));
        }

        BigDecimal totalEstimatedCostAvg = row.get(TOTAL_ESTIMATED_COST_AVG, BigDecimal.class);
        if (totalEstimatedCostAvg == null) {
            totalEstimatedCostAvg = BigDecimal.ZERO;
        }

        stats.add(new AvgValueStat(TOTAL_ESTIMATED_COST, totalEstimatedCostAvg.doubleValue()));

        BigDecimal totalEstimatedCostSum = row.get(TOTAL_ESTIMATED_COST_SUM, BigDecimal.class);
        if (totalEstimatedCostSum == null) {
            totalEstimatedCostSum = BigDecimal.ZERO;
        }

        stats.add(new AvgValueStat(TOTAL_ESTIMATED_COST_SUM, totalEstimatedCostSum.doubleValue()));

        addMapStats(row, USAGE, stats);
        addMapStats(row, USAGE_SUM, stats);
        addMapStats(row, FEEDBACK_SCORE, stats);
        // Only add span feedback scores statistics for traces (not spans)
        if (entityCountLabel.equals(TRACE_COUNT) && row.getMetadata().contains(SPAN_FEEDBACK_SCORE)) {
            addMapStats(row, SPAN_FEEDBACK_SCORE, stats);
        }

        // spans cannot accept guardrails and therefore will not have guardrails_failed_count in the result set
        if (row.getMetadata().contains(GUARDRAILS_FAILED_COUNT)) {
            Optional.ofNullable(row.get(GUARDRAILS_FAILED_COUNT, Long.class)).ifPresent(
                    guardrailsFailedCount -> stats
                            .add(new CountValueStat(GUARDRAILS_FAILED_COUNT, guardrailsFailedCount)));
        }

        Long recentErrorCount = Optional.ofNullable(row)
                .filter(r -> r.getMetadata().contains(RECENT_ERROR_COUNT))
                .map(r -> r.get(RECENT_ERROR_COUNT, Long.class))
                .orElse(null);

        Long pastPeriodErrorCount = Optional.ofNullable(row)
                .filter(r -> r.getMetadata().contains(PAST_PERIOD_ERROR_COUNT))
                .map(r -> r.get(PAST_PERIOD_ERROR_COUNT, Long.class))
                .orElse(null);

        Long errorCount = null;

        // If mapping project stats, the error has to be calculated from recent and past period error counts
        if (recentErrorCount != null) {
            stats.add(new CountValueStat(RECENT_ERROR_COUNT, recentErrorCount));
            errorCount = recentErrorCount;
        }

        if (pastPeriodErrorCount != null) {
            stats.add(new CountValueStat(PAST_PERIOD_ERROR_COUNT, pastPeriodErrorCount));

            errorCount = errorCount == null ? pastPeriodErrorCount : errorCount + pastPeriodErrorCount;
        }

        // Otherwise, if the error count is not calculated from recent and past period error counts
        if (errorCount == null && row.getMetadata().contains(ERROR_COUNT)) {
            errorCount = row.get(ERROR_COUNT, Long.class);
        }

        if (errorCount != null) {
            stats.add(new CountValueStat(ERROR_COUNT, errorCount));
        }

        return new ProjectStats(stats.build().toList());
    }

    private static BigDecimal getP(List<BigDecimal> durations, int index) {
        return durations.get(index);
    }

    public static Map<String, Double> getStatsUsage(Map<String, Object> stats) {
        return Optional.ofNullable(stats)
                .map(map -> map.keySet()
                        .stream()
                        .filter(k -> k.startsWith(USAGE))
                        .map(
                                k -> Map.entry(k.substring("%s.".formatted(USAGE).length()), (Double) map.get(k)))
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .orElse(null);
    }

    private static Double getStatAsDouble(Map<String, ?> stats, String key) {
        return Optional.ofNullable(stats)
                .map(map -> map.get(key))
                .map(v -> (Double) v)
                .orElse(null);
    }

    public static Double getStatsTotalEstimatedCostSum(Map<String, ?> stats) {
        return getStatAsDouble(stats, TOTAL_ESTIMATED_COST_SUM);
    }

    public static Double getStatsTotalEstimatedCost(Map<String, ?> stats) {
        return getStatAsDouble(stats, TOTAL_ESTIMATED_COST);
    }

    public static List<FeedbackScoreAverage> getStatsFeedbackScores(Map<String, ?> stats) {
        return Optional.ofNullable(stats)
                .map(map -> map.keySet()
                        .stream()
                        .filter(k -> k.startsWith(FEEDBACK_SCORE))
                        .map(k -> new FeedbackScoreAverage(k.substring("%s.".formatted(FEEDBACK_SCORE).length()),
                                BigDecimal.valueOf((Double) map.get(k))))
                        .toList())
                .orElse(null);
    }

    public static PercentageValues getStatsDuration(Map<String, ?> stats) {
        return Optional.ofNullable(stats)
                .map(map -> (PercentageValues) map.get(DURATION))
                .orElse(null);
    }

    public static Long getStatsTraceCount(Map<String, Object> projectStats) {
        return Optional.ofNullable(projectStats)
                .map(map -> (Long) map.get(TRACE_COUNT))
                .orElse(null);
    }

    public static Long getStatsThreadCount(Map<String, Object> projectStats) {
        return Optional.ofNullable(projectStats)
                .map(map -> (Long) map.get(THREAD_COUNT))
                .orElse(null);
    }

    public static Long getStatsGuardrailsFailedCount(Map<String, Object> projectStats) {
        return Optional.ofNullable(projectStats)
                .map(map -> (Long) map.get(GUARDRAILS_FAILED_COUNT))
                .orElse(null);
    }

    public static ErrorCountWithDeviation getStatsErrorCount(Map<String, Object> projectStats) {
        if (projectStats == null) {
            return null;
        }

        Long recentErrorCount = (Long) projectStats.get(RECENT_ERROR_COUNT);
        Long partPeriodErrorCount = (Long) projectStats.get(PAST_PERIOD_ERROR_COUNT);

        long recentErrorTotal = recentErrorCount + partPeriodErrorCount;

        Long deviationPercentage = null;
        if (partPeriodErrorCount > 0) {
            // Calculate the percentage change between recent errors and historical errors
            deviationPercentage = Long
                    .valueOf(Math.round(((recentErrorTotal - partPeriodErrorCount) / partPeriodErrorCount) * 100));
        }

        return ErrorCountWithDeviation.builder()
                .count(recentErrorTotal)
                .deviation(recentErrorCount)
                .deviationPercentage(deviationPercentage)
                .build();
    }

    public static ProjectStats mapExperimentItemsStats(Row row) {
        var stats = Stream.<ProjectStats.ProjectStatItem<?>>builder();

        stats.add(new CountValueStat(EXPERIMENT_ITEMS_COUNT,
                row.get(EXPERIMENT_ITEMS_COUNT, Long.class)));

        stats.add(new CountValueStat(TRACE_COUNT,
                row.get(TRACE_COUNT, Long.class)));

        BigDecimal totalEstimatedCostAvg = getCostValue(row, TOTAL_ESTIMATED_COST_AVG);
        if (totalEstimatedCostAvg != null) {
            stats.add(new AvgValueStat(TOTAL_ESTIMATED_COST,
                    totalEstimatedCostAvg.doubleValue()));
        }

        // Add total_estimated_cost percentiles
        addPercentilesFromMap(row, TOTAL_ESTIMATED_COST_PERCENTILES, TOTAL_ESTIMATED_COST, stats);

        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> durationMap = row.get(DURATION, Map.class);
        if (durationMap != null) {
            var duration = new PercentageValues(
                    durationMap.get("p50"),
                    durationMap.get("p90"),
                    durationMap.get("p99"));
            stats.add(new PercentageValueStat(DURATION, duration));
        }

        addMapStats(row, FEEDBACK_SCORE, stats);
        addMapStats(row, USAGE, stats);

        // Add feedback_scores percentiles (map of score_name -> {p50, p90, p99})
        addFeedbackScoresPercentiles(row, stats);

        // Add usage.total_tokens percentiles
        addPercentilesFromMap(row, USAGE_TOTAL_TOKENS_PERCENTILES, "usage.total_tokens", stats);

        return new ProjectStats(stats.build().toList());
    }

    /**
     * Adds percentile stats from a map field containing {p50, p90, p99} values.
     */
    @SuppressWarnings("unchecked")
    private static void addPercentilesFromMap(
            Row row,
            String fieldName,
            String statName,
            Stream.Builder<ProjectStats.ProjectStatItem<?>> statsBuilder) {
        Map<String, ?> percentilesMap = row.get(fieldName, Map.class);
        if (percentilesMap != null && !percentilesMap.isEmpty()) {
            BigDecimal p50 = toBigDecimal(percentilesMap.get("p50"));
            BigDecimal p90 = toBigDecimal(percentilesMap.get("p90"));
            BigDecimal p99 = toBigDecimal(percentilesMap.get("p99"));

            if (p50 != null || p90 != null || p99 != null) {
                var percentiles = PercentageValues.builder().p50(p50).p90(p90).p99(p99).build();
                statsBuilder.add(new PercentageValueStat(statName, percentiles));
            }
        }
    }

    /**
     * Adds feedback scores percentiles from the feedback_scores_percentiles field.
     * The field contains a map of score_name -> {p50, p90, p99}.
     */
    @SuppressWarnings("unchecked")
    private static void addFeedbackScoresPercentiles(
            Row row,
            Stream.Builder<ProjectStats.ProjectStatItem<?>> statsBuilder) {
        Map<String, Map<String, ?>> feedbackScoresPercentilesMap = row.get(FEEDBACK_SCORES_PERCENTILES, Map.class);
        if (MapUtils.isNotEmpty(feedbackScoresPercentilesMap)) {
            feedbackScoresPercentilesMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String scoreName = entry.getKey();
                        Map<String, ?> percentilesMap = entry.getValue();
                        if (percentilesMap != null && !percentilesMap.isEmpty()) {
                            BigDecimal p50 = toBigDecimal(percentilesMap.get("p50"));
                            BigDecimal p90 = toBigDecimal(percentilesMap.get("p90"));
                            BigDecimal p99 = toBigDecimal(percentilesMap.get("p99"));

                            if (p50 != null || p90 != null || p99 != null) {
                                var percentiles = PercentageValues.builder().p50(p50).p90(p90).p99(p99).build();
                                statsBuilder.add(new PercentageValueStat(
                                        "%s.%s".formatted(FEEDBACK_SCORE, scoreName),
                                        percentiles));
                            }
                        }
                    });
        }
    }

    /**
     * Converts a value to BigDecimal, handling various numeric types.
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return null;
    }

    /**
     * Extracts a map from a row and adds its values as AvgValueStat entries to the stats builder.
     * Converts all numeric values to doubles and sorts entries by key.
     *
     * @param row The database row containing the map field
     * @param fieldName The field name to extract from the row (e.g., "feedback_scores", "usage")
     * @param statsBuilder The builder to add stats to
     */
    @SuppressWarnings("unchecked")
    private static void addMapStats(
            Row row,
            String fieldName,
            Stream.Builder<ProjectStats.ProjectStatItem<?>> statsBuilder) {
        Map<String, Object> map = row.get(fieldName, Map.class);
        if (map != null) {
            map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        double value = entry.getValue() instanceof Number number
                                ? number.doubleValue()
                                : 0.0;
                        statsBuilder.add(new AvgValueStat(
                                "%s.%s".formatted(fieldName, entry.getKey()),
                                value));
                    });
        }
    }

    private static BigDecimal getCostValue(Row row, String columnName) {
        return row.get(columnName, BigDecimal.class);
    }
}
