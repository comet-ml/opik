package com.comet.opik.systemmetrics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SystemMetricSeries(
        UUID projectId,
        String serviceInstanceId,
        String metricName,
        String unit,
        Instant from,
        Instant to,
        boolean truncated,
        List<SystemMetricSample> points) {
}
