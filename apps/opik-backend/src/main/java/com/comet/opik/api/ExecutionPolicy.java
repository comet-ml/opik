package com.comet.opik.api;

import com.comet.opik.api.validation.ExecutionPolicyValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@ExecutionPolicyValidation
public record ExecutionPolicy(
        @Min(1) @Max(100) int runsPerItem,
        @Min(1) @Max(100) int passThreshold) {

    public static final ExecutionPolicy DEFAULT = new ExecutionPolicy(1, 1);
}
