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
        Boolean truncate) {

    /**
     * Determines whether trace messages should be truncated.
     *
     * @return true if truncate is explicitly set to true, false otherwise.
     *         This means both null and false values result in no truncation,
     *         making truncation opt-in rather than default behavior.
     */
    public boolean shouldTruncate() {
        return truncate != null && truncate;
    }
}
