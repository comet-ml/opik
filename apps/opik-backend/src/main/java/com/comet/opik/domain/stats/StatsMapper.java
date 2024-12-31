package com.comet.opik.domain.stats;

import com.comet.opik.api.FeedbackScoreAverage;
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
import static com.comet.opik.api.ProjectStats.PercentageValues;
import static java.util.stream.Collectors.toMap;

public class StatsMapper {

    public static final String USAGE = "usage";
    public static final String FEEDBACK_SCORE = "feedback_scores";
    public static final String TOTAL_ESTIMATED_COST = "total_estimated_cost";
    public static final String DURATION = "duration";
    public static final String INPUT = "input";
    public static final String OUTPUT = "output";
    public static final String METADATA = "metadata";
    public static final String TAGS = "tags";

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

        BigDecimal totalEstimatedCost = row.get("total_estimated_cost_avg", BigDecimal.class);
        if (totalEstimatedCost == null) {
            totalEstimatedCost = BigDecimal.ZERO;
        }

        stats.add(new AvgValueStat(TOTAL_ESTIMATED_COST, totalEstimatedCost.doubleValue()));

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

    public static Double getStatsTotalEstimatedCost(Map<String, ?> stats) {
        return Optional.ofNullable(stats)
                .map(map -> map.get(TOTAL_ESTIMATED_COST))
                .map(v -> (Double) v)
                .orElse(null);
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
}
