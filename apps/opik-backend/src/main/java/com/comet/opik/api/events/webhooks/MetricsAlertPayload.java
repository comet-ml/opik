package com.comet.opik.api.events.webhooks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * Data Transfer Object for metrics alert payload.
 * Contains threshold evaluation results and metadata.
 *
 * Scalar fields (metricValue, threshold, windowSeconds, feedbackScoreName) are populated from the
 * first contributing condition for backward compatibility with single-condition consumers. The
 * complete list of contributing conditions for an AND-group firing is exposed via {@link #conditions}.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MetricsAlertPayload(
        String eventType,
        String metricName,
        @NonNull String metricValue,
        @NonNull String threshold,
        long windowSeconds,
        String projectIds,
        String projectNames,
        String feedbackScoreName,
        Integer groupIndex,
        List<Condition> conditions) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Condition(
            String metricName,
            @NonNull String metricValue,
            @NonNull String threshold,
            long windowSeconds,
            String feedbackScoreName,
            String operator) {
    }
}