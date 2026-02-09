package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TraceThreadUpdate(
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tags,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags to add") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tagsToAdd,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags to remove") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tagsToRemove) {
}
