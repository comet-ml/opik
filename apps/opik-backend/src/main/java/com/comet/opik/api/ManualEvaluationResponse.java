package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * Response model for manual evaluation requests.
 * Provides confirmation details about the evaluation job that was queued.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ManualEvaluationResponse(
        @Schema(description = "Number of entities queued for evaluation", example = "5") int entitiesQueued,
        @Schema(description = "Number of rules that will be applied", example = "2") int rulesApplied) {
}
