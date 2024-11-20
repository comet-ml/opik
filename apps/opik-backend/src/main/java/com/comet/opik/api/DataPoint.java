package com.comet.opik.api;

import java.time.Instant;

public record DataPoint<T>(Instant time, T value) {}
