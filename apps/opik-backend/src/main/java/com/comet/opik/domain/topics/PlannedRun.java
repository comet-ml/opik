package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunMode;
import lombok.Builder;
import lombok.NonNull;

import java.time.LocalDate;
import java.util.UUID;

/** A run to queue: its message once queued, which gives it its id. */
@Builder(toBuilder = true)
record PlannedRun(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull RunMode mode, LocalDate night,
        long windowTraces) {

    TopicsRunMessage message(UUID runId) {
        return new TopicsRunMessage(runId, workspaceId, projectId, mode, night, windowTraces);
    }
}
