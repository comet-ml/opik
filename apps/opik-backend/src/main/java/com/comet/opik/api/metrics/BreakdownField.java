package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Supported group by dimensions for dashboard widget metrics.
 * Each field represents a dimension by which metrics can be grouped.
 * Simplified to only include commonly used fields.
 */
@RequiredArgsConstructor
@Getter
public enum BreakdownField {

    NONE("none", "No Grouping", false),
    TAGS("tags", "Tags", false),
    METADATA("metadata", "Metadata", true),
    NAME("name", "Name", false),
    ERROR_INFO("error_info", "Has Error", false);

    @JsonValue
    private final String value;
    private final String displayName;
    private final boolean requiresKey;

    /**
     * Check if this group by field is compatible with the given metric type.
     * All fields are compatible with all metric types for simplicity.
     */
    public boolean isCompatibleWith(MetricType metricType) {
        return true;
    }
}
