package com.comet.opik.domain.llm;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.NonNull;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelCapability(@NonNull String name, @NonNull String litellmProvider, boolean supportsVision) {
}
