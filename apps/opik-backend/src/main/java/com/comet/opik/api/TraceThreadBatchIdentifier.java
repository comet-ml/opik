package com.comet.opik.api;

import com.comet.opik.api.validation.TraceThreadBatchIdentifierValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

/**
 * Identifier for batch thread operations that supports both single and multiple thread IDs.
 * This DTO maintains backward compatibility with TraceThreadIdentifier while allowing batch operations.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@TraceThreadBatchIdentifierValidation
public record TraceThreadBatchIdentifier(
        String projectName,
        UUID projectId,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String threadId, // For backward compatibility
        @Size(min = 1, max = 1000) List<@NotBlank String> threadIds) { // For batch operations
}
