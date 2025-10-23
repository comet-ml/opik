package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Request model for manually triggering evaluation rules on selected entities.
 * This allows users to run online evaluation metrics on specific traces or threads
 * without sampling, directly from the UI.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ManualEvaluationRequest(
        @NotNull @Schema(description = "Project ID", example = "550e8400-e29b-41d4-a716-446655440000") UUID projectId,
        @NotEmpty @Schema(description = "List of entity IDs (trace IDs or thread IDs) to evaluate", example = "[\"550e8400-e29b-41d4-a716-446655440000\", \"550e8400-e29b-41d4-a716-446655440001\"]") List<UUID> entityIds,
        @NotEmpty @Schema(description = "List of automation rule IDs to apply", example = "[\"660e8400-e29b-41d4-a716-446655440000\"]") List<UUID> ruleIds,
        @NotNull @Schema(description = "Type of entity to evaluate (trace or thread)", example = "trace") ManualEvaluationEntityType entityType) {
}
