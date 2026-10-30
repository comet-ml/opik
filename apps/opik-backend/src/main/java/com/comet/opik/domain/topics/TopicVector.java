package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of {@code topic_vectors}: the unit-length vector of one trace's summary for one task version. */
@Builder(toBuilder = true)
public record TopicVector(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull UUID traceId,
        @NonNull Instant traceCreatedAt,
        int taskVersion,
        @NonNull String embedModel,
        int embedDimensions,
        @NonNull String text,
        @NonNull List<Float> vector) {

    public TopicVector {
        vector = List.copyOf(vector);
    }
}
