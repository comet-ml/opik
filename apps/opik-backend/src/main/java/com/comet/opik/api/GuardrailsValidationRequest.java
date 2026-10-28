package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.Set;

/**
 * One text and the checks to run on it: those given here, the rules of the stored policies it names and those of
 * every global policy of the workspace. Each check is a type plus the user's settings for it, the same shape as a
 * policy's rule.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailsValidationRequest(
        @NotBlank String text,
        @Schema(description = "Checks to run as given. Optional when policies are named") @Nullable @Size(max = 32) @Valid List<ValidationDescriptor> validations,
        @Schema(description = "Names of stored guardrail policies whose rules are checked as well. Every global policy of the workspace is checked whether or not it is named") @Nullable @Size(max = 1000) Set<@NotBlank String> policies) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ValidationDescriptor(
            @NotNull GuardrailType type,
            @Schema(implementation = JsonNode.class, ref = "JsonNode", description = "User settings only: PII entities (each supported class at most once), or allowed_topics and restricted_topics (at least one; each up to 254 distinct labels of up to 256 characters, not 'none of these', and no topic in both). Thresholds are set by the server and rejected here.") @NotNull JsonNode config) {
    }
}
