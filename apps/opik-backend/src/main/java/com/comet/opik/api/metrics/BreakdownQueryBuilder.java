package com.comet.opik.api.metrics;

import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.api.metrics.BreakdownField.SPAN_METRICS;

public class BreakdownQueryBuilder {

    // Fixed defaults - not configurable
    public static final int LIMIT = 10;
    public static final String OTHERS_GROUP_NAME = "__others__";
    public static final String UNKNOWN_GROUP_NAME = "Unknown";

    /**
     * Check if breakdown is enabled (field is not NONE and not null).
     */
    public static boolean isEnabled(BreakdownConfig config) {
        return config.field() != null && config.field() != BreakdownField.NONE;
    }

    /**
     * Validate the configuration.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public static void validate(BreakdownConfig config, MetricType metricType) {
        if (!isEnabled(config)) {
            return;
        }

        if (config.field() == BreakdownField.METADATA && StringUtils.isBlank(config.metadataKey())) {
            throw new IllegalArgumentException("metadata_key is required when group by field is 'metadata'");
        }

        if (!config.field().isCompatibleWith(metricType)) {
            throw new IllegalArgumentException(
                    "Group by field '%s' is not compatible with metric type '%s'. This field supports %s."
                            .formatted(config.field().getValue(), metricType.name(),
                                    config.field().getCompatibleMetricTypesDescription()));
        }
    }

    /**
     * Get the SQL expression for the breakdown group based on the metric type and breakdown field.
     * This is used in the GROUP BY clause to group metrics by the specified dimension.
     * The table alias prefix depends on the metric type:
     * - Span metrics use 's.' prefix (spans_filtered)
     * - Trace/Thread metrics use 't.' prefix (traces_filtered/threads_filtered)
     */
    public static String getBreakdownGroupExpression(MetricType metricType, BreakdownConfig breakdown) {
        if (breakdown == null || !isEnabled(breakdown)) {
            return "''";
        }

        // Span metrics use 's.' prefix, trace/thread metrics use 't.' prefix
        if (SPAN_METRICS.contains(metricType)) {
            return getSpanBreakdownExpression(breakdown);
        } else {
            return getTraceOrThreadBreakdownExpression(breakdown);
        }
    }

    /**
     * Maps sub-metric names (p50, p90, p99) to ClickHouse quantile values (0.5, 0.9, 0.99).
     */
    public static String mapSubMetric(String subMetric) {
        return switch (subMetric.toLowerCase()) {
            case "p50" -> "0.5";
            case "p90" -> "0.9";
            case "p99" -> "0.99";
            default -> subMetric;
        };
    }

    /**
     * Get breakdown expression for span metrics using 's.' table alias.
     */
    private static String getSpanBreakdownExpression(BreakdownConfig breakdown) {
        return switch (breakdown.field()) {
            case TAGS -> "arrayJoin(if(empty(s.tags), ['Unknown'], s.tags))";
            case METADATA -> "ifNull(JSONExtractString(s.metadata, :metadata_key), 'Unknown')";
            case NAME -> "ifNull(s.name, 'Unknown')";
            case ERROR_INFO -> "if(length(s.error_info) > 0, 'Has Error', 'No Error')";
            case MODEL -> "if(s.model = '', 'Unknown', s.model)";
            case PROVIDER -> "if(s.provider = '', 'Unknown', s.provider)";
            case TYPE -> "toString(s.type)";
            default -> "''";
        };
    }

    /**
     * Get breakdown expression for trace/thread metrics using 't.' table alias.
     */
    private static String getTraceOrThreadBreakdownExpression(BreakdownConfig breakdown) {
        return switch (breakdown.field()) {
            case TAGS -> "arrayJoin(if(empty(t.tags), ['Unknown'], t.tags))";
            case METADATA -> "ifNull(JSONExtractString(t.metadata, :metadata_key), 'Unknown')";
            case NAME -> "ifNull(t.name, 'Unknown')";
            case ERROR_INFO -> "if(length(t.error_info) > 0, 'Has Error', 'No Error')";
            default -> "''";
        };
    }
}
