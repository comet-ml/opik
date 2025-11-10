package com.comet.opik.api;

import com.comet.opik.api.validation.TraceThreadIdentifierValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@TraceThreadIdentifierValidation
public record TraceThreadIdentifier(
        String projectName,
        UUID projectId,
        @NotBlank String threadId,
        boolean truncate) {
}
