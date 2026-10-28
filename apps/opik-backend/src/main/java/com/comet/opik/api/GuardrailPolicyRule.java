package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * One check of a policy. The config uses the same fields as the guardrails validations contract, so a policy's
 * rules can be sent to the check as they are stored.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailPolicyRule(
        @NotNull GuardrailType type,
        @Schema(implementation = JsonNode.class, ref = "JsonNode", description = "User settings only: PII entities, or topics and a mode ('allow' or 'restrict'). Empty for prompt injection. Thresholds are set by the server and rejected here.") @NotNull JsonNode config) {
}
