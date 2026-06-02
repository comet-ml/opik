package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
public record TraceSummary(
        @NonNull UUID traceId,
        @NonNull UUID projectId,
        @NonNull String summary,
        Instant createdAt,
        Instant lastUpdatedAt) {
}
