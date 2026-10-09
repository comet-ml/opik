package com.comet.opik.systemmetrics;

import java.time.Instant;

public record SystemMetricInstance(
        String serviceInstanceId,
        String serviceName,
        String agentId,
        Instant lastSeen) {
}
