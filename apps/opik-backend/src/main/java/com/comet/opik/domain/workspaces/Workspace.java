package com.comet.opik.domain.workspaces;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;

/**
 * Internal workspace metadata record backing the {@code workspaces} state-DB table. The record is
 * consumed by JDBI3's {@code ConstructorMapper} only — never serialised to JSON, so no Jackson
 * annotations are needed.
 *
 * <p>{@code lastKnownVersion} is the raw DB string ({@code version_1} / {@code version_2} / null);
 * the service layer converts it to {@link com.comet.opik.api.OpikVersion} via
 * {@link com.comet.opik.api.OpikVersion#findByValue}, treating unrecognised values as empty.
 */
@Builder(toBuilder = true)
public record Workspace(
        @NonNull String id,
        String lastKnownVersion,
        Instant versionDeterminedAt,
        Instant firstTraceReportedAt,
        Instant migrationSkippedAt,
        String migrationSkippedReason,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy) {
}
