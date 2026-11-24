package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One attempt to train a guardrail model. It carries its workspace because the run worker reads runs across
 * workspaces and needs each run's workspace for its writes and its S3 keys. Neither the workspace nor the metadata,
 * which holds opik-inference's job details, is part of the API. The API only returns runs, so every field it shows is
 * read-only; OpenAPI marks properties, not schemas, so each field says so.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailTrainingRun(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @NotNull UUID id,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @NotNull UUID modelId,
        @JsonIgnore String workspaceId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @NotNull GuardrailTrainingRunStatus status,
        @Schema(description = "Why the run ended failed or cancelled; null otherwise", accessMode = Schema.AccessMode.READ_ONLY) @Nullable String statusMessage,
        @Schema(description = "Which attempt of one training the run is, 1 for the first", accessMode = Schema.AccessMode.READ_ONLY) int attempt,
        @JsonIgnore JsonNode metadata,
        @Schema(description = "When the run reached completed, failed or cancelled; null while it is in flight", accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant finishedAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @NotNull Instant createdAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @NotNull String createdBy,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @NotNull Instant lastUpdatedAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @NotNull String lastUpdatedBy) {

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
