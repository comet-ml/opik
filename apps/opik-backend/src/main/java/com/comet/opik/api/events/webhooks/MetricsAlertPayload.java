package com.comet.opik.api.events.webhooks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

/**
 * Data Transfer Object for metrics alert payload.
 * Contains threshold evaluation results and metadata.
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
        String feedbackScoreName) {
}
