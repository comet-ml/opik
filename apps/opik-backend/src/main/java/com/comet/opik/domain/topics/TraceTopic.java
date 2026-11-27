package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code trace_topics}: the topic a trace was assigned to, {@link #OTHER} for Other.
 *
 * @param runId the run that wrote the row; null for an arrival row
 * @param lastUpdatedAt the row's {@code last_updated_at}; null to have ClickHouse stamp the insert time
 */
@Builder(toBuilder = true)
public record TraceTopic(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull UUID traceId,
        @NonNull Instant traceCreatedAt,
        @NonNull UUID topicId,
        @NonNull Source source,
        @Nullable UUID runId,
        @Nullable Instant lastUpdatedAt) {

    /** The topic id of Other. */
    public static final UUID OTHER = new UUID(0L, 0L);

    @Getter
    @RequiredArgsConstructor
    public enum Source implements HasValue {
        RUN("run"),
        ARRIVAL("arrival");

        private final String value;
    }
}
