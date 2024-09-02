package com.comet.opik.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
public record SpanModel(
        @NonNull UUID id,
        @NonNull UUID traceId,
        @NonNull UUID parentSpanId,
        @NonNull String name,
        @NonNull SpanType type,
        @NonNull Instant startTime,
        Instant endTime,
        @NonNull JsonNode input,
        @NonNull JsonNode output,
        @NonNull JsonNode metadata,
        @NonNull Set<String> tags,
        @NonNull Map<String, Integer> usage,
        @NonNull Instant createdAt,
        @NonNull Instant lastUpdatedAt) {
}
