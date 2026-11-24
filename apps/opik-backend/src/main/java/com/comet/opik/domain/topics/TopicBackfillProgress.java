package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/** What one tick's backfill did for a project: drained it, or not ({@code drainedAt} null), and wrote vectors. */
@Builder(toBuilder = true)
public record TopicBackfillProgress(@NonNull String workspaceId, @NonNull UUID projectId, @Nullable Instant drainedAt,
        long written) {
}
