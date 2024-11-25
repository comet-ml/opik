package com.comet.opik.domain.stats;

import com.comet.opik.api.ProjectStats;
import io.r2dbc.spi.Row;

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

    private static double getP(List<Double> durations, int index) {
        Double duration = durations.get(index);

        if (duration.isNaN()) {
            return 0;
        }

        return duration;
    }

}
