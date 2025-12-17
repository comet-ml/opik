package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

/**
 * Configuration for Optimization Studio runs.
 * This represents the full payload sent from the frontend to create a Studio optimization.
 *
 * The opikApiKey is internal-only: populated server-side from the request header, never serialized to clients.
 * Required for cloud deployments, as it'll be used to automate SDK in behalf of the user.
 */
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizationStudioConfig(
        @NotBlank String datasetName,
        @NotNull @Valid StudioPrompt prompt,
        @NotNull @Valid StudioLlmModel llmModel,
        @NotNull @Valid StudioEvaluation evaluation,
        @NotNull @Valid StudioOptimizer optimizer,
        @JsonIgnore String opikApiKey) {

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioPrompt(
            @NotEmpty @Valid List<StudioMessage> messages) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioMessage(
            @NotBlank String role,
            @NotBlank String content) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioLlmModel(
            @NotBlank String model,
            JsonNode parameters) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioEvaluation(
            @NotEmpty @Valid List<StudioMetric> metrics) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioMetric(
            @NotBlank String type,
            JsonNode parameters) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioOptimizer(
            @NotBlank String type,
            JsonNode parameters) {
    }
}
