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
public record LocalRunnerJob(
        String id,
        String runnerId,
        String agentName,
        String status,
        JsonNode inputs,
        JsonNode result,
        String error,
        String project,
        String traceId,
        Integer timeout,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LocalRunnerJobPage(
            int page,
            int size,
            long total,
            List<LocalRunnerJob> content) implements Page<LocalRunnerJob> {
    }
}
