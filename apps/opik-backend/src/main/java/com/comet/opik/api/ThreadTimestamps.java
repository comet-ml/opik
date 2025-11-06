package com.comet.opik.api;

import java.time.Instant;
import java.util.UUID;

public record ThreadTimestamps(UUID firstTraceId, Instant lastUpdatedAt) {
}
