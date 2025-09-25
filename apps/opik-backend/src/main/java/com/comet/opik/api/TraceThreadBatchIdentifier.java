package com.comet.opik.api;

import com.comet.opik.api.validation.TraceThreadBatchIdentifierValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * Identifier for batch thread operations that supports both single and multiple thread IDs.
 * This DTO maintains backward compatibility with {@link TraceThreadIdentifier} while allowing batch operations.
 *
 * <p>The identifier supports two modes of operation:</p>
 * <ul>
 *   <li><strong>Single mode:</strong> Use {@code threadId} for single thread operations</li>
 *   <li><strong>Batch mode:</strong> Use {@code threadIds} for batch operations (up to 1000 threads)</li>
 * </ul>
 *
 * <p>Project identification can be done using either {@code projectName} or {@code projectId}.</p>
 *
 * @param projectName The name of the project (mutually exclusive with projectId)
 * @param projectId The UUID of the project (mutually exclusive with projectName)
 * @param threadId Single thread ID for backward compatibility (mutually exclusive with threadIds)
 * @param threadIds Set of thread IDs for batch operations (mutually exclusive with threadId, max 1000 items)
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@TraceThreadBatchIdentifierValidation
public record TraceThreadBatchIdentifier(
        @Schema String projectName,
        @Schema UUID projectId,
        @Schema String threadId,
        @Schema @Size(min = 1, max = 1000) Set<@NotBlank String> threadIds) {
}
