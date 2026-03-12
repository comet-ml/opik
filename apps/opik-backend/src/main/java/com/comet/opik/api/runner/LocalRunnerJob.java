package com.comet.opik.api.runner;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LocalRunnerJob(
        UUID id,
        UUID runnerId,
        String agentName,
        LocalRunnerJobStatus status,
        JsonNode inputs,
        JsonNode result,
        String error,
        UUID projectId,
        UUID traceId,
        UUID maskId,
        LocalRunnerJobMetadata metadata,
        int timeout,
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
