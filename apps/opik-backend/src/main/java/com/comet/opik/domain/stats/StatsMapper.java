package com.comet.opik.domain.stats;

import com.comet.opik.api.ProjectStats;
import io.r2dbc.spi.Row;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class StatsMapper {

    public static ProjectStats mapProjectStats(Row row, String entityCountLabel) {

        var stats = Stream.<ProjectStats.ProjectStatItem<?>>builder()
                .add(new ProjectStats.CountValueStat(entityCountLabel,
                        row.get(entityCountLabel, Long.class)))
                .add(new ProjectStats.PercentageValueStat("duration", Optional
                        .ofNullable(row.get("duration", List.class))
                        .map(durations -> new ProjectStats.PercentageValues(
                                getP(durations, 0),
                                getP(durations, 1),
                                getP(durations, 2)))
                        .orElse(null)))
                .add(new ProjectStats.CountValueStat("input", row.get("input", Long.class)))
                .add(new ProjectStats.CountValueStat("output", row.get("output", Long.class)))
                .add(new ProjectStats.CountValueStat("metadata", row.get("metadata", Long.class)))
                .add(new ProjectStats.AvgValueStat("tags", row.get("tags", Double.class)));

        BigDecimal totalEstimatedCost = row.get("total_estimated_cost_avg", BigDecimal.class);
        if (totalEstimatedCost == null) {
            totalEstimatedCost = BigDecimal.ZERO;
        }

        stats.add(new ProjectStats.AvgValueStat("total_estimated_cost", totalEstimatedCost.doubleValue()));

        Map<String, Double> usage = row.get("usage", Map.class);
        Map<String, Double> feedbackScores = row.get("feedback_scores", Map.class);

        if (usage != null) {
            usage.keySet()
                    .stream()
                    .sorted()
                    .forEach(key -> stats
                            .add(new ProjectStats.AvgValueStat("%s.%s".formatted("usage", key), usage.get(key))));
        }

        if (feedbackScores != null) {
            feedbackScores.keySet()
                    .stream()
                    .sorted()
                    .forEach(key -> stats.add(new ProjectStats.AvgValueStat("%s.%s".formatted("feedback_score", key),
                            feedbackScores.get(key))));
        }

        return new ProjectStats(stats.build().toList());
    }

    private static BigDecimal getP(List<BigDecimal> durations, int index) {
        return durations.get(index);
    }

}
