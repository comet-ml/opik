package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelCostData(String litellmProvider,
        String inputCostPerToken,
        String outputCostPerToken,
        String cacheCreationInputTokenCost,
        String cacheReadInputTokenCost,
        Boolean supportsVision) {
}
