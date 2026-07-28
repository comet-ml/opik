package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

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
        // Jackson's SnakeCaseStrategy doesn't insert an underscore between letters and digits,
        // so the camelCase fields below would resolve to keys like "above200k" without an
        // underscore. Pin each tier field to the literal LiteLLM JSON key with @JsonProperty.
        @JsonProperty("input_cost_per_token_above_128k_tokens") String inputCostPerTokenAbove128kTokens,
        @JsonProperty("output_cost_per_token_above_128k_tokens") String outputCostPerTokenAbove128kTokens,
        @JsonProperty("input_cost_per_token_above_200k_tokens") String inputCostPerTokenAbove200kTokens,
        @JsonProperty("output_cost_per_token_above_200k_tokens") String outputCostPerTokenAbove200kTokens,
        @JsonProperty("cache_creation_input_token_cost_above_200k_tokens") String cacheCreationInputTokenCostAbove200kTokens,
        @JsonProperty("cache_read_input_token_cost_above_200k_tokens") String cacheReadInputTokenCostAbove200kTokens,
        @JsonProperty("input_cost_per_token_above_272k_tokens") String inputCostPerTokenAbove272kTokens,
        @JsonProperty("output_cost_per_token_above_272k_tokens") String outputCostPerTokenAbove272kTokens,
        String mode,
        boolean supportsVision,
        String aliasOf) {
}
