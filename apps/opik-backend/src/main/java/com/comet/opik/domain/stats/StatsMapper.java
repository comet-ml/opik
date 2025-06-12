package com.comet.opik.domain.stats;

import com.comet.opik.api.ErrorCountWithDeviation;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import io.r2dbc.spi.Row;

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
    public static final String FEEDBACK_SCORE = "feedback_scores";
    public static final String TOTAL_ESTIMATED_COST = "total_estimated_cost";
    public static final String TOTAL_ESTIMATED_COST_AVG = "total_estimated_cost_avg";
    public static final String TOTAL_ESTIMATED_COST_SUM = "total_estimated_cost_sum";
    public static final String DURATION = "duration";
    public static final String INPUT = "input";
    public static final String OUTPUT = "output";
    public static final String METADATA = "metadata";
    public static final String TAGS = "tags";
    public static final String TRACE_COUNT = "trace_count";
    public static final String GUARDRAILS_FAILED_COUNT = "guardrails_failed_count";
    public static final String RECENT_ERROR_COUNT = "recent_error_count";
    public static final String PAST_PERIOD_ERROR_COUNT = "past_period_error_count";
    public static final String ERROR_COUNT = "error_count";

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
                .add(new CountValueStat(INPUT, row.get("input", Long.class)))
                .add(new CountValueStat(OUTPUT, row.get("output", Long.class)))
                .add(new CountValueStat(METADATA, row.get("metadata", Long.class)))
                .add(new AvgValueStat(TAGS, row.get("tags", Double.class)));

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

        Map<String, Double> usage = row.get(USAGE, Map.class);
        Map<String, Double> feedbackScores = row.get(FEEDBACK_SCORE, Map.class);

        if (usage != null) {
            usage.keySet()
                    .stream()
                    .sorted()
                    .forEach(key -> stats
                            .add(new AvgValueStat("%s.%s".formatted(USAGE, key), usage.get(key))));
        }

        if (feedbackScores != null) {
            feedbackScores.keySet()
                    .stream()
                    .sorted()
                    .forEach(key -> stats.add(new AvgValueStat("%s.%s".formatted(FEEDBACK_SCORE, key),
                            feedbackScores.get(key))));
        }

        // spans cannot accept guardrails and therefore will not have guardrails_failed_count in the result set
        if (row.getMetadata().contains("guardrails_failed_count")) {
            Optional.ofNullable(row.get("guardrails_failed_count", Long.class)).ifPresent(
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
}
