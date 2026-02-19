package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RunnerJob(
        String id,
        String runnerId,
        String agentName,
        String status,
        JsonNode inputs,
        JsonNode result,
        String error,
        String stdout,
        String project,
        String traceId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
}
