package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

/**
 * Configuration for grouping metrics by a specific dimension.
 * Simplified configuration - always shows top 10 groups by value descending,
 * with remaining groups aggregated into "Others".
 *
 * @param field       The field to group by
 * @param metadataKey Required when field is METADATA - the key to extract from metadata JSON
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BreakdownConfig(
        BreakdownField field,
        String metadataKey) {

    // Fixed defaults - not configurable
    public static final int LIMIT = 10;
    public static final String OTHERS_GROUP_NAME = "__others__";
    public static final String OTHERS_DISPLAY_NAME = "Others";
    public static final String UNKNOWN_GROUP_NAME = "Unknown";

    /**
     * Check if breakdown is enabled (field is not NONE and not null).
     */
    public boolean isEnabled() {
        return field != null && field != BreakdownField.NONE;
    }

    /**
     * Validate the configuration.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate(MetricType metricType) {
        if (!isEnabled()) {
            return;
        }

        if (field == BreakdownField.METADATA && (metadataKey == null || metadataKey.isBlank())) {
            throw new IllegalArgumentException("metadata_key is required when group by field is 'metadata'");
        }

        if (!field.isCompatibleWith(metricType)) {
            throw new IllegalArgumentException(
                    "Group by field '%s' is not compatible with metric type '%s'"
                            .formatted(field.getValue(), metricType.name()));
        }
    }

    /**
     * Creates a default config with no grouping.
     */
    public static BreakdownConfig none() {
        return BreakdownConfig.builder()
                .field(BreakdownField.NONE)
                .build();
    }
}
