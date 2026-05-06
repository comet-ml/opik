package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;

/**
 * Internal workspace metadata record backing the {@code workspaces} state-DB table.
 *
 * <p>{@code lastKnownVersion} is the raw DB string ({@code version_1}/{@code version_2}/{@code null});
 * the service layer converts it to {@link OpikVersion} via {@link OpikVersion#findByValue}, treating
 * unrecognised values as empty.
 */
@Builder(toBuilder = true)
public record Workspace(
        @JsonProperty("id") String id,
        @JsonProperty("last_known_version") String lastKnownVersion,
        @JsonProperty("version_determined_at") Instant versionDeterminedAt,
        @JsonProperty("first_trace_reported_at") Instant firstTraceReportedAt,
        @JsonProperty("migration_skipped_at") Instant migrationSkippedAt,
        @JsonProperty("migration_skipped_reason") String migrationSkippedReason,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("last_updated_at") Instant lastUpdatedAt,
        @JsonProperty("last_updated_by") String lastUpdatedBy) {
}
