package com.comet.opik.systemmetrics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SystemMetricPoint(
        @NotNull UUID sampleId,
        @NotBlank @Size(max = 128) String serviceName,
        @NotBlank @Size(max = 128) String serviceInstanceId,
        @Size(max = 128) String agentId,
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,127}") String metricName,
        @NotBlank @Size(max = 32) String unit,
        @NotNull Instant timestamp,
        @NotNull Double value,
        @Size(max = 16) Map<String, String> attributes) {
}
