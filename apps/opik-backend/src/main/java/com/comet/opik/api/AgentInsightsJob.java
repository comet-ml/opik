package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentInsightsJob(
        @JsonIgnore String workspaceId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        UUID projectId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Status status,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastTriggeredAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    @RequiredArgsConstructor
    @Getter
    public enum Status {
        ENABLED("enabled"),
        DISABLED("disabled");

        @JsonValue
        private final String value;

        @JsonCreator
        public static Status fromString(String value) {
            for (Status status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown agent insights job status: " + value);
        }
    }

    /**
     * Lightweight cross-workspace projection used by the scheduler (OPIK-6853). Unlike the
     * user-facing queries, this intentionally carries {@code workspaceId} because the cron runs in a
     * system context and needs it to scope the trace check and the trigger payload.
     */
    @Builder(toBuilder = true)
    public record EnabledJob(@NonNull UUID id, @NonNull String workspaceId, @NonNull UUID projectId) {
    }
}
