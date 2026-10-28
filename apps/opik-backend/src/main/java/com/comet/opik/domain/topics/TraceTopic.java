package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code trace_topics}: the topic a trace was assigned to, {@link #OTHER} for Other. */
@Builder(toBuilder = true)
public record TraceTopic(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull UUID traceId,
        @NonNull Instant traceCreatedAt,
        @NonNull UUID topicId,
        @NonNull Source source,
        @NonNull String runId) {

    /** The topic id of Other. */
    public static final UUID OTHER = new UUID(0L, 0L);

    @Getter
    @RequiredArgsConstructor
    public enum Source {
        NIGHT("night"),
        ARRIVAL("arrival");

        private final String value;
    }
}
