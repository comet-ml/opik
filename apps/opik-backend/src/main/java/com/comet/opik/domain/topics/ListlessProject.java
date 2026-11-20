package com.comet.opik.domain.topics;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A project with no saved list and no run in flight, with what its backfill gate and its retry policy read. The
 * backfill fields are those of {@link TopicProject}; null or 0 when the project has no backfill.
 *
 * @param vectorsCheckedAt the last time the vectors tick checked the project's workspace, null when it never did
 */
@Builder(toBuilder = true)
public record ListlessProject(@NonNull String workspaceId, @NonNull UUID projectId,
        @Nullable Instant backfillStartedAt, @Nullable Instant backfillDrainedAt, long backfillTracesTotal,
        long backfillTracesDone, int failedRuns, @Nullable Instant firstFailedAt, @Nullable Instant runFinishedAt,
        @Nullable Instant vectorsCheckedAt) {
}
