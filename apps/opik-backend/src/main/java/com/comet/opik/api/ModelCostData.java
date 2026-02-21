package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
        String mode,
        boolean supportsVision) {
}
