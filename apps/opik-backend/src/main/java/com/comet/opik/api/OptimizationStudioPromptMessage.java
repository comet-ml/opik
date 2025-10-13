package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizationStudioPromptMessage(
        @JsonView({
                OptimizationStudioRun.View.Public.class,
                OptimizationStudioRun.View.Write.class}) @NotNull String role,
        @JsonView({
                OptimizationStudioRun.View.Public.class,
                OptimizationStudioRun.View.Write.class}) @NotNull String content) {
}
