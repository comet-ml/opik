package com.comet.opik.api;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record DataPoint<T extends Number>(Instant time, T value) {}
