package com.comet.opik.domain;

import com.comet.opik.api.OptimizationStudioConfig;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * Request message for Optimization Studio jobs sent to Python optimizer service via Redis RQ.
 * <p>
 * This message is enqueued to the Python backend worker and contains all necessary
 * information to execute an optimization job using the Opik SDK.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Builder(toBuilder = true)
public record OptimizationStudioJobMessage(
        @NonNull UUID optimizationId,
        @NonNull String workspaceId,
        @NonNull OptimizationStudioConfig config) {
}
