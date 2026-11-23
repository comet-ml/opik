package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A window read of {@link TopicVectorsDAO#findUnvectored}: done statuses of {@code task} updated in
 * {@code [lowerBound, upperBound]}, of traces created at or after {@code createdAfter}, without a vector, oldest first.
 * Narrowed to the traces created before {@code createdBefore} when it is set (a slice), to {@code projectId} when it is
 * set, and to the projects not in {@code excludedProjectIds}, empty when not set.
 */
@Builder(toBuilder = true)
public record TopicVectorsFind(
        @NonNull String workspaceId,
        @NonNull String task,
        @NonNull Instant lowerBound,
        @NonNull Instant upperBound,
        @NonNull Instant createdAfter,
        @Nullable Instant createdBefore,
        @Nullable UUID projectId,
        Collection<UUID> excludedProjectIds,
        int limit,
        long maxExecutionSeconds) {

    public TopicVectorsFind {
        excludedProjectIds = Objects.requireNonNullElse(excludedProjectIds, List.of());
    }
}
