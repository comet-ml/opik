package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A single row of the deletion-events bridge table. {@code eventTime} is null on the insert path (ClickHouse
 * stamps it) and {@code projectId} is null for workspace-scoped source tables; {@code deletedId} is a plain
 * string because it is not a UUID for every source table.
 */
@Builder(toBuilder = true)
public record DeletionEvent(
        Instant eventTime,
        @NonNull SourceTable sourceTable,
        @NonNull String workspaceId,
        UUID projectId,
        @NonNull String deletedId,
        @NonNull DeletionReason deletionReason) {
}
