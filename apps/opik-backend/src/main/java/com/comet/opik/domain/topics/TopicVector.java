package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code topic_vectors}: the unit-length vector of one trace's summary for one task and task version.
 * Compare instances field by field (the array has identity equality).
 */
@Builder(toBuilder = true)
public record TopicVector(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull String task,
        @NonNull UUID traceId,
        @NonNull Instant traceCreatedAt,
        int taskVersion,
        @NonNull String embedModel,
        int embedDimensions,
        @NonNull String text,
        float @NonNull [] vector) {
}
