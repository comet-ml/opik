package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;

@Builder(toBuilder = true)
public record TimeBucket(
        @NonNull Instant start,
        @NonNull Instant end) {
}
