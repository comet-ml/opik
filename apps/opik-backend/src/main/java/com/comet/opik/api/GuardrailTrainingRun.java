package com.comet.opik.api;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to train a {@link GuardrailModel}. It carries its workspace because the run worker reads runs across
 * workspaces and needs each run's workspace for its writes and its S3 keys.
 */
@Builder(toBuilder = true)
public record GuardrailTrainingRun(
        UUID id,
        UUID modelId,
        String workspaceId,
        GuardrailTrainingRunStatus status,
        String statusMessage,
        int attempt,
        JsonNode metadata,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy) {
}
