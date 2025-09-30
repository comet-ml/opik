package com.comet.opik.domain.llm;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelCapability(String name, String litellmProvider, boolean supportsVision) {
}
