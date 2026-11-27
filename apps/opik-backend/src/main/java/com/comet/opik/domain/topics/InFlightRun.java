package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A project's run that is queued, running or cancelling, as the planner reconciles it.
 *
 * @param projectDeleted the project's row in {@code projects} is gone
 */
@Builder(toBuilder = true)
public record InFlightRun(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId,
        @NonNull TopicProject.RunMode runMode, @NonNull TopicProject.RunStatus runStatus, @Nullable Instant runQueuedAt,
        @Nullable Instant deadlineAt, @Nullable Instant heartbeatAt, boolean projectDeleted) {
}
