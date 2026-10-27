package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/** One trace of a nightly run's window: its latest task version's intent text and vector from {@code topic_vectors}. */
@Builder(toBuilder = true)
public record TopicWindowRow(
        @NonNull UUID traceId,
        @NonNull Instant traceCreatedAt,
        @NonNull String text,
        @NonNull float[] vector) {
}
