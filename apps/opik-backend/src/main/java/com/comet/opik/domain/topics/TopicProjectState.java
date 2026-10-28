package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code topic_project_states}: a project with a saved run. {@code rebuilds} counts the saved nights and seeds
 * the next run's k-means; {@code windowUntil} is the end of the last saved run's window.
 */
@Builder(toBuilder = true)
public record TopicProjectState(
        @NonNull UUID projectId,
        @NonNull String workspaceId,
        int rebuilds,
        @Nullable Instant firstBuiltAt,
        @Nullable UUID lastRunId,
        @Nullable Instant lastSuccessAt,
        @Nullable Instant windowUntil) {
}
