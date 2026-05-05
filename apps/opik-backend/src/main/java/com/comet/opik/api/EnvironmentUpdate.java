package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EnvironmentUpdate(
        @Pattern(regexp = Environment.NAME_PATTERN, message = Environment.NAME_PATTERN_MESSAGE) @Size(max = 150, message = "cannot exceed 150 characters") String name,
        @Size(max = 500, message = "cannot exceed 500 characters") String description,
        @Size(max = 20, message = "cannot exceed 20 characters") String color,
        Integer position) {
}
