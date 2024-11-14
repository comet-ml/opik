package com.comet.opik.api;

import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

public record ProjectIdLastUpdated(@NonNull UUID id, @NonNull Instant lastUpdatedAt) {
}
