package com.comet.opik.api;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
public record ProjectIdLastUpdated(@NonNull UUID id, @NonNull Instant lastUpdatedAt) {
}
