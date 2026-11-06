package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

/**
 * A verdict per check, in the order the checks were sent, plus the overall verdict. Same shape as the v1
 * contract, so the SDK reports each entry to the results pipeline unchanged. An entry whose check could not run
 * carries {@code error} and passes: guardrails fail open.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailsValidationResponse(
        boolean validationPassed,
        @NotNull List<ValidationResult> validations) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ValidationResult(
            boolean validationPassed,
            @NotNull GuardrailType type,
            @Schema(implementation = JsonNode.class, ref = "JsonNode") @NotNull JsonNode validationConfig,
            @Schema(implementation = JsonNode.class, ref = "JsonNode") @NotNull JsonNode validationDetails,
            @Schema(description = "Set when the check could not run; the entry then passes") String error) {
    }
}
