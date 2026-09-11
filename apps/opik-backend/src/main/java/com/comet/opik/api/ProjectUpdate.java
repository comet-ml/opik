package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectUpdate(
        // Not Blank makes the field required, while this pattern allows null values and validates the string if it is not null
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String name,
        @Size(max = 255, message = "cannot exceed 255 characters") String description,
        Visibility visibility) {
}
