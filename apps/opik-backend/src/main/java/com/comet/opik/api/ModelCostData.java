package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Builder(toBuilder = true)
public record ModelCostData(String litellmProvider,
        String inputCostPerToken,
        String outputCostPerToken,
        String outputCostPerVideoPerSecond,
        String inputCostPerCharacter,
        String cacheCreationInputTokenCost,
        String cacheReadInputTokenCost,
        String inputCostPerAudioToken,
        String outputCostPerAudioToken,
        String outputCostPerReasoningToken,
        // Jackson's SnakeCaseStrategy doesn't insert an underscore between letters and digits, so pin the literal key.
        @JsonProperty("cache_creation_input_token_cost_above_1hr") String cacheCreationInputTokenCostAbove1hr,
        String mode,
        boolean supportsVision,
        String aliasOf,
        // The entry's other fields, among them the prompt-size tier rates (*_above_<N>k_tokens) at every threshold.
        @JsonAnySetter Map<String, JsonNode> otherFields) {

    public ModelCostData {
        otherFields = otherFields == null ? Map.of() : otherFields;
    }
}
