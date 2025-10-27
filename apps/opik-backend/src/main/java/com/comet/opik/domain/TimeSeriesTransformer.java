package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Transforms ProjectStats (single point) into time-series DataPoints.
 * Extracts specific metrics from stats and creates time-series data for dashboards.
 */
@Slf4j
public class TimeSeriesTransformer {

    /**
     * Transform a single ProjectStats into DataPoint for a specific trace metric.
     *
     * @param stats      The aggregated stats for a time bucket
     * @param metricType The metric to extract
     * @param bucketTime The time for this bucket
     * @return List of DataPoints (can be multiple for metrics like feedback_scores that have multiple series)
     */
    public static List<DataPoint<? extends Number>> transformToDataPoints(
            @NonNull ProjectStats stats,
            @NonNull MetricType metricType,
            @NonNull Instant bucketTime) {

        if (stats.stats().isEmpty()) {
            log.debug("Empty stats for metric '{}' at time '{}'", metricType, bucketTime);
            return List.of();
        }

        return switch (metricType) {
            // Simple count metrics
            case TRACE_COUNT -> extractSimpleCount(stats, "trace_count", bucketTime);
            case INPUT_COUNT -> extractSimpleCount(stats, "input", bucketTime);
            case OUTPUT_COUNT -> extractSimpleCount(stats, "output", bucketTime);
            case METADATA_COUNT -> extractSimpleCount(stats, "metadata", bucketTime);
            case ERROR_COUNT -> extractSimpleCount(stats, "error_count", bucketTime);
            case GUARDRAILS_FAILED_COUNT -> extractSimpleCount(stats, "guardrails_failed_count", bucketTime);

            // Average metrics
            case SPAN_COUNT -> extractSimpleAvg(stats, "span_count", bucketTime);
            case LLM_SPAN_COUNT -> extractSimpleAvg(stats, "llm_span_count", bucketTime);
            case TAGS_AVERAGE -> extractSimpleAvg(stats, "tags", bucketTime);

            // Cost metrics
            case COST -> extractSimpleNumber(stats, "total_estimated_cost_sum", bucketTime);
            case AVG_COST_PER_TRACE -> extractSimpleNumber(stats, "total_estimated_cost", bucketTime);

            // Duration metrics (P50/P90/P99)
            case DURATION -> extractPercentiles(stats, "duration", bucketTime, "duration");

            // Map-based metrics
            case TOKEN_USAGE -> extractMapValues(stats, "usage", bucketTime);
            case COMPLETION_TOKENS -> extractMapValue(stats, "usage", "completion_tokens", bucketTime);
            case PROMPT_TOKENS -> extractMapValue(stats, "usage", "prompt_tokens", bucketTime);
            case TOTAL_TOKENS -> extractMapValue(stats, "usage", "total_tokens", bucketTime);
            case FEEDBACK_SCORES -> extractMapValues(stats, "feedback_scores", bucketTime);

            // Calculated metrics (requires computation)
            case TRACE_WITH_ERRORS_PERCENT -> calculateErrorPercent(stats, bucketTime);
            case GUARDRAILS_PASS_RATE -> calculateGuardrailsPassRate(stats, bucketTime);

            // Not yet implemented via stats endpoint
            case THREAD_COUNT, THREAD_DURATION, THREAD_FEEDBACK_SCORES ->
                throw new UnsupportedOperationException(
                        "Metric '%s' requires different data source".formatted(metricType));

            // Span metrics should use transformToSpanDataPoints() instead
            case SPAN_TOTAL_COUNT, SPAN_ERROR_COUNT, SPAN_INPUT_COUNT, SPAN_OUTPUT_COUNT,
                    SPAN_METADATA_COUNT, SPAN_TAGS_AVERAGE, SPAN_COST, SPAN_AVG_COST,
                    SPAN_FEEDBACK_SCORES, SPAN_TOKEN_USAGE, SPAN_PROMPT_TOKENS,
                    SPAN_COMPLETION_TOKENS, SPAN_TOTAL_TOKENS, SPAN_DURATION ->
                throw new IllegalArgumentException(
                        "Span metric '%s' should use transformToSpanDataPoints()".formatted(metricType));
        };
    }

    /**
     * Transform a single ProjectStats (from span stats endpoint) into DataPoint for a specific span metric.
     *
     * @param stats      The aggregated span stats for a time bucket
     * @param metricType The span metric to extract
     * @param bucketTime The time for this bucket
     * @return List of DataPoints
     */
    public static List<DataPoint<? extends Number>> transformToSpanDataPoints(
            @NonNull ProjectStats stats,
            @NonNull MetricType metricType,
            @NonNull Instant bucketTime) {

        if (stats.stats().isEmpty()) {
            log.debug("Empty span stats for metric '{}' at time '{}'", metricType, bucketTime);
            return List.of();
        }

        return switch (metricType) {
            // Span count metric (note: span stats uses "span_count" for total count)
            case SPAN_TOTAL_COUNT -> extractSimpleCount(stats, "span_count", bucketTime);

            // Span data metrics
            case SPAN_INPUT_COUNT -> extractSimpleCount(stats, "input", bucketTime);
            case SPAN_OUTPUT_COUNT -> extractSimpleCount(stats, "output", bucketTime);
            case SPAN_METADATA_COUNT -> extractSimpleCount(stats, "metadata", bucketTime);
            case SPAN_ERROR_COUNT -> extractSimpleCount(stats, "error_count", bucketTime);

            // Span average metrics
            case SPAN_TAGS_AVERAGE -> extractSimpleAvg(stats, "tags", bucketTime);

            // Span cost metrics
            case SPAN_COST -> extractSimpleNumber(stats, "total_estimated_cost_sum", bucketTime);
            case SPAN_AVG_COST -> extractSimpleNumber(stats, "total_estimated_cost", bucketTime);

            // Span duration
            case SPAN_DURATION -> extractPercentiles(stats, "duration", bucketTime, "span_duration");

            // Span token metrics
            case SPAN_TOKEN_USAGE -> extractMapValues(stats, "usage", bucketTime);
            case SPAN_COMPLETION_TOKENS -> extractMapValue(stats, "usage", "completion_tokens", bucketTime);
            case SPAN_PROMPT_TOKENS -> extractMapValue(stats, "usage", "prompt_tokens", bucketTime);
            case SPAN_TOTAL_TOKENS -> extractMapValue(stats, "usage", "total_tokens", bucketTime);

            // Span feedback scores
            case SPAN_FEEDBACK_SCORES -> extractMapValues(stats, "feedback_scores", bucketTime);

            // Non-span metrics should use transformToDataPoints() instead
            default ->
                throw new IllegalArgumentException(
                        "Non-span metric '%s' should use transformToDataPoints()".formatted(metricType));
        };
    }

    /**
     * Extract a simple count metric.
     */
    @SuppressWarnings("unchecked")
    private static List<DataPoint<? extends Number>> extractSimpleCount(
            ProjectStats stats, String statName, Instant time) {

        List<DataPoint<Long>> dataPoints = stats.stats().stream()
                .filter(stat -> stat.getName().equals(statName))
                .filter(stat -> stat instanceof ProjectStats.CountValueStat)
                .map(stat -> (ProjectStats.CountValueStat) stat)
                .map(stat -> DataPoint.<Long>builder()
                        .time(time)
                        .value(stat.getValue())
                        .build())
                .toList();

        return (List<DataPoint<? extends Number>>) (List<?>) dataPoints;
    }

    /**
     * Extract a simple average metric.
     */
    @SuppressWarnings("unchecked")
    private static List<DataPoint<? extends Number>> extractSimpleAvg(
            ProjectStats stats, String statName, Instant time) {

        List<DataPoint<Double>> dataPoints = stats.stats().stream()
                .filter(stat -> stat.getName().equals(statName))
                .filter(stat -> stat instanceof ProjectStats.AvgValueStat)
                .map(stat -> (ProjectStats.AvgValueStat) stat)
                .map(stat -> DataPoint.<Double>builder()
                        .time(time)
                        .value(stat.getValue())
                        .build())
                .toList();

        return (List<DataPoint<? extends Number>>) (List<?>) dataPoints;
    }

    /**
     * Extract a generic number metric.
     */
    @SuppressWarnings("unchecked")
    private static List<DataPoint<? extends Number>> extractSimpleNumber(
            ProjectStats stats, String statName, Instant time) {

        List<DataPoint<Number>> dataPoints = stats.stats().stream()
                .filter(stat -> stat.getName().equals(statName))
                .map(stat -> DataPoint.<Number>builder()
                        .time(time)
                        .value((Number) stat.getValue())
                        .build())
                .toList();

        return (List<DataPoint<? extends Number>>) (List<?>) dataPoints;
    }

    /**
     * Extract percentile metrics (duration).
     * Note: This extracts only P50 for simplicity. For full P50/P90/P99 support,
     * use the dedicated DAO queries (OLD implementation).
     */
    private static List<DataPoint<? extends Number>> extractPercentiles(
            ProjectStats stats, String statName, Instant time, String prefix) {

        return stats.stats().stream()
                .filter(stat -> stat.getName().equals(statName))
                .filter(stat -> stat instanceof ProjectStats.PercentageValueStat)
                .map(stat -> (ProjectStats.PercentageValueStat) stat)
                .findFirst()
                .map(stat -> {
                    PercentageValues percentages = stat.getValue();

                    // For now, only extract P50 as a single series
                    // Full P50/P90/P99 requires more complex grouping
                    if (percentages.p50() != null) {
                        return List.<DataPoint<? extends Number>>of(
                                DataPoint.<BigDecimal>builder()
                                        .time(time)
                                        .value(percentages.p50())
                                        .build());
                    }

                    return List.<DataPoint<? extends Number>>of();
                })
                .orElse(List.of());
    }

    /**
     * Extract all values from a map-based metric (e.g., usage, feedback_scores).
     * Creates multiple series, one per map key.
     */
    @SuppressWarnings("unchecked")
    private static List<DataPoint<? extends Number>> extractMapValues(
            ProjectStats stats, String statName, Instant time) {

        // Note: Stats endpoint returns maps as separate stat items
        // Each key becomes a separate stat with pattern: "statName.key"
        List<DataPoint<Number>> dataPoints = stats.stats().stream()
                .filter(stat -> stat.getName().startsWith(statName + ".") || stat.getName().equals(statName))
                .map(stat -> DataPoint.<Number>builder()
                        .time(time)
                        .value((Number) stat.getValue())
                        .build())
                .toList();

        return (List<DataPoint<? extends Number>>) (List<?>) dataPoints;
    }

    /**
     * Extract a single value from a map-based metric.
     */
    @SuppressWarnings("unchecked")
    private static List<DataPoint<? extends Number>> extractMapValue(
            ProjectStats stats, String mapName, String key, Instant time) {

        String fullName = mapName + "." + key;
        List<DataPoint<Number>> dataPoints = stats.stats().stream()
                .filter(stat -> stat.getName().equals(fullName))
                .map(stat -> DataPoint.<Number>builder()
                        .time(time)
                        .value((Number) stat.getValue())
                        .build())
                .toList();

        return (List<DataPoint<? extends Number>>) (List<?>) dataPoints;
    }

    /**
     * Calculate error percentage: (error_count / trace_count) * 100
     */
    private static List<DataPoint<? extends Number>> calculateErrorPercent(
            ProjectStats stats, Instant time) {

        Long errorCount = stats.stats().stream()
                .filter(stat -> stat.getName().equals("error_count"))
                .filter(stat -> stat instanceof ProjectStats.CountValueStat)
                .map(stat -> ((ProjectStats.CountValueStat) stat).getValue())
                .findFirst()
                .orElse(0L);

        Long traceCount = stats.stats().stream()
                .filter(stat -> stat.getName().equals("trace_count"))
                .filter(stat -> stat instanceof ProjectStats.CountValueStat)
                .map(stat -> ((ProjectStats.CountValueStat) stat).getValue())
                .findFirst()
                .orElse(0L);

        if (traceCount == 0) {
            return List.of(DataPoint.<BigDecimal>builder()
                    .time(time)
                    .value(BigDecimal.ZERO)
                    .build());
        }

        BigDecimal percent = BigDecimal.valueOf(errorCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(traceCount), 2, RoundingMode.HALF_UP);

        return List.of(DataPoint.<BigDecimal>builder()
                .time(time)
                .value(percent)
                .build());
    }

    /**
     * Calculate guardrails pass rate: ((total - failed) / total) * 100
     */
    private static List<DataPoint<? extends Number>> calculateGuardrailsPassRate(
            ProjectStats stats, Instant time) {

        Long failedCount = stats.stats().stream()
                .filter(stat -> stat.getName().equals("guardrails_failed_count"))
                .filter(stat -> stat instanceof ProjectStats.CountValueStat)
                .map(stat -> ((ProjectStats.CountValueStat) stat).getValue())
                .findFirst()
                .orElse(0L);

        // Note: We need total guardrails count which might not be in stats
        // For now, if failed > 0, we can't calculate pass rate without total
        // This might need to be addressed
        if (failedCount == 0) {
            return List.of(DataPoint.<BigDecimal>builder()
                    .time(time)
                    .value(BigDecimal.valueOf(100))
                    .build());
        }

        // Placeholder - needs total guardrails count
        return List.of(DataPoint.<BigDecimal>builder()
                .time(time)
                .value(BigDecimal.ZERO)
                .build());
    }

    /**
     * Group data points by series name for response.
     */
    @SuppressWarnings("unchecked")
    public static List<ProjectMetricResponse.Results<? extends Number>> groupBySeriesName(
            List<DataPoint<? extends Number>> dataPoints, String seriesName) {

        if (dataPoints.isEmpty()) {
            return List.of();
        }

        List<DataPoint<Number>> typedDataPoints = (List<DataPoint<Number>>) (List<?>) dataPoints;

        return List.of(ProjectMetricResponse.Results.<Number>builder()
                .name(seriesName)
                .data(typedDataPoints)
                .build());
    }
}
