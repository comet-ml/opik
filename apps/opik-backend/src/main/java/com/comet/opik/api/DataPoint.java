package com.comet.opik.api;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record DataPoint(Instant time, Number value) {
}
