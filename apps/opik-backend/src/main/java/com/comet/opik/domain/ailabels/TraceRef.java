package com.comet.opik.domain.ailabels;

import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

public record TraceRef(@NonNull UUID projectId, @NonNull UUID traceId, @NonNull Instant createdAt) {
}
