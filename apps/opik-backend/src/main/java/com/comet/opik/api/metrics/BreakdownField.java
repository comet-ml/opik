package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Set;

/**
 * Supported group by dimensions for dashboard widget metrics.
 * Each field represents a dimension by which metrics can be grouped.
 * Compatibility with metric types is based on entity type:
 * - Trace metrics: DURATION, TRACE_COUNT, TOKEN_USAGE, COST, FEEDBACK_SCORES, GUARDRAILS_FAILED_COUNT
 * - Thread metrics: THREAD_COUNT, THREAD_DURATION, THREAD_FEEDBACK_SCORES
 * - Span metrics: SPAN_COUNT, SPAN_DURATION, SPAN_TOKEN_USAGE, SPAN_FEEDBACK_SCORES
 */
@RequiredArgsConstructor
@Getter
public enum BreakdownField {

    NONE("none", "No Grouping", false),
    PROJECT_ID("project_id", "Project", false),
    TAGS("tags", "Tags", false),
    METADATA("metadata", "Metadata", true),
    NAME("name", "Name", false),
    ERROR_INFO("error_info", "Has Error", false),
    MODEL("model", "Model", false),
    PROVIDER("provider", "Provider", false),
    TYPE("type", "Span Type", false);

    @JsonValue
    private final String value;
    private final String displayName;
    private final boolean requiresKey;

    // Trace-based metrics
    private static final Set<MetricType> TRACE_METRICS = EnumSet.of(
            MetricType.DURATION,
            MetricType.TRACE_COUNT,
            MetricType.TOKEN_USAGE,
            MetricType.COST,
            MetricType.FEEDBACK_SCORES,
            MetricType.GUARDRAILS_FAILED_COUNT);

    // Thread-based metrics
    private static final Set<MetricType> THREAD_METRICS = EnumSet.of(
            MetricType.THREAD_COUNT,
            MetricType.THREAD_DURATION,
            MetricType.THREAD_FEEDBACK_SCORES);

    // Span-based metrics
    private static final Set<MetricType> SPAN_METRICS = EnumSet.of(
            MetricType.SPAN_COUNT,
            MetricType.SPAN_DURATION,
            MetricType.SPAN_TOKEN_USAGE,
            MetricType.SPAN_FEEDBACK_SCORES);

    /**
     * Check if this group by field is compatible with the given metric type.
     * Based on the Jira ticket OPIK-3790 "Supported Breakdown Fields" table:
     * - PROJECT_ID: Trace, Span, Thread
     * - TAGS: Trace, Span, Thread
     * - METADATA: Trace, Span (not Thread)
     * - NAME: Trace, Span (not Thread)
     * - ERROR_INFO: Trace, Span (not Thread)
     * - MODEL: Spans only
     * - PROVIDER: Spans only
     * - TYPE: Spans only
     */
    public boolean isCompatibleWith(MetricType metricType) {
        if (this == NONE) {
            return true;
        }

        return switch (this) {
            case PROJECT_ID, TAGS -> TRACE_METRICS.contains(metricType)
                    || SPAN_METRICS.contains(metricType)
                    || THREAD_METRICS.contains(metricType);
            case METADATA, NAME, ERROR_INFO -> TRACE_METRICS.contains(metricType)
                    || SPAN_METRICS.contains(metricType);
            case MODEL, PROVIDER, TYPE -> SPAN_METRICS.contains(metricType);
            default -> false;
        };
    }

    /**
     * Get a user-friendly description of which metric types are compatible with this field.
     */
    public String getCompatibleMetricTypesDescription() {
        return switch (this) {
            case NONE -> "all metrics";
            case PROJECT_ID, TAGS -> "Trace, Span, and Thread metrics";
            case METADATA, NAME, ERROR_INFO -> "Trace and Span metrics";
            case MODEL, PROVIDER, TYPE -> "Span metrics only";
        };
    }
}
