package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One attempt to train a guardrail model. It carries its workspace because the run worker reads runs across
 * workspaces and needs each run's workspace for its writes and its S3 keys. Neither the workspace nor the metadata,
 * which holds opik-inference's job details, is part of the API.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(accessMode = Schema.AccessMode.READ_ONLY)
public record GuardrailTrainingRun(
        UUID id,
        UUID modelId,
        @JsonIgnore String workspaceId,
        GuardrailTrainingRunStatus status,
        @Schema(description = "Why the run ended failed or cancelled; null otherwise") String statusMessage,
        @Schema(description = "Which attempt of one training the run is, 1 for the first; a retryable failure is retried as the next attempt") int attempt,
        @JsonIgnore JsonNode metadata,
        Instant createdAt,
        String createdBy,
        @Schema(description = "A finished run's last update is when it finished") Instant lastUpdatedAt,
        String lastUpdatedBy) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GuardrailTrainingRunPage(
            List<GuardrailTrainingRun> content,
            int page,
            int size,
            long total) implements Page<GuardrailTrainingRun> {
    }
}
