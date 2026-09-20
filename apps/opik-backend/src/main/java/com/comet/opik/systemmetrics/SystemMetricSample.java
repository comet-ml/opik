package com.comet.opik.systemmetrics;

import java.time.Instant;
import java.util.Map;

public record SystemMetricSample(
        Instant timestamp,
        double value,
        Map<String, String> attributes) {
}
