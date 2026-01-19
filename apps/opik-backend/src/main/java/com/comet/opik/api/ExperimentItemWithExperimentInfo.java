package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Response model for experiment item with associated experiment information.
 * Used when looking up experiment items by trace_id.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentItemWithExperimentInfo(
        @Schema(description = "Experiment item ID") UUID id,
        @Schema(description = "Experiment ID") UUID experimentId,
        @Schema(description = "Dataset item ID") UUID datasetItemId,
        @Schema(description = "Trace ID") UUID traceId,
        @Schema(description = "Experiment name") String experimentName,
        @Schema(description = "Dataset ID") UUID datasetId,
        @Schema(description = "Creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant lastUpdatedAt,
        @Schema(description = "Created by user") String createdBy,
        @Schema(description = "Last updated by user") String lastUpdatedBy) {
}
