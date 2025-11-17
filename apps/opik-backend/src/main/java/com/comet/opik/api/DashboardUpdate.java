package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DashboardUpdate(
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Size(min = 1, max = 120, message = "name must be between 1 and 120 characters") String name,
        @Nullable @Size(max = 1000, message = "description cannot exceed 1000 characters") String description,
        JsonNode config) {
}
