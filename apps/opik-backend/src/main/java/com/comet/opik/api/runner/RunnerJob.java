package com.comet.opik.api.runner;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

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
        String project,
        String traceId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunnerJobPage(
            int page,
            int size,
            long total,
            List<RunnerJob> content) implements Page<RunnerJob> {
    }
}
