package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A (trace, task version) whose latest {@code ai_label_status} row is done and that has no vector yet.
 * {@code statusUpdatedAt} is that row's {@code updated_at}: the tick takes the oldest first.
 */
@Builder(toBuilder = true)
public record TopicVectorCandidate(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull UUID traceId,
        @NonNull Instant traceCreatedAt,
        int taskVersion,
        @NonNull Instant statusUpdatedAt) {
}
