package com.comet.opik.api.events.webhooks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.math.BigDecimal;

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
        @NonNull BigDecimal metricValue,
        @NonNull BigDecimal threshold,
        long windowSeconds,
        String projectIds) {
}
