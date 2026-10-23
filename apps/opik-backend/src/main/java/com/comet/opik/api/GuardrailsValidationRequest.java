package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

/**
 * One text and the checks to run on it. Same shape as the v1 validations contract of the Python guardrails
 * service, so the SDK's request building carries over: each check is a type plus the user's settings for it.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailsValidationRequest(
        @NotBlank String text,
        @NotNull @Size(min = 1, max = 32) @Valid List<ValidationDescriptor> validations) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ValidationDescriptor(
            @NotNull GuardrailType type,
            @Schema(implementation = JsonNode.class, ref = "JsonNode", description = "User settings only: PII entities (each supported class at most once), or topics (1 to 254 distinct labels of up to 256 characters, not 'none of these') and mode. Thresholds are set by the server and rejected here.") @NotNull JsonNode config) {
    }
}
