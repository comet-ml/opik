package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code topic_run_counts}: a live topic's traces in a saved run's window, and its 1-based rank in the
 * run. The Other row has {@link TraceTopic#OTHER} as {@code topicId} and no rank. {@code savedAt} is the run's
 * {@code finished_at}.
 */
@Builder(toBuilder = true)
public record TopicRunCount(
        @NonNull UUID runId,
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull UUID topicId,
        long traces,
        @Nullable Integer rank,
        @NonNull Instant savedAt) {
}
