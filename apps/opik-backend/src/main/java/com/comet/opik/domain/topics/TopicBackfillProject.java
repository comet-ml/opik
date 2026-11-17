package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A project the backfill's discovery found: {@code traces} done status rows of traces created in the backfill window,
 * and the latest done status written in it.
 */
@Builder(toBuilder = true)
public record TopicBackfillProject(@NonNull UUID projectId, long traces, @NonNull Instant lastDoneAt) {
}
