package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * One check of a policy, holding only what the user set. A topic rule can carry both an allowed and a restricted
 * list, since a policy holds one rule per type.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailPolicyRule(
        @NotNull GuardrailType type,
        @Schema(implementation = JsonNode.class, ref = "JsonNode", description = "User settings only: PII entities, or topic allowed_topics and restricted_topics (at least one). Empty for prompt injection. Thresholds are set by the server and rejected here.") @NotNull JsonNode config) {
}
