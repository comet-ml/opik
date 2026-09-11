package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.List;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueueUpdate(
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String name,
        String description,
        String instructions,
        Boolean commentsEnabled,
        List<@NotBlank String> feedbackDefinitionNames,
        @Min(1) @Max(1000) Integer annotatorsPerItem,
        @Min(1) @Max(3600) Integer lockTimeoutSeconds,
        // Three-state on purpose: null leaves the existing automation untouched, {"enabled": false}
        // disables it while keeping its conditions (and the routing ledger, so re-enabling does not
        // re-route history), and a full object replaces the conditions. There is no delete.
        @Valid AnnotationQueueAutomation automation) {
}
