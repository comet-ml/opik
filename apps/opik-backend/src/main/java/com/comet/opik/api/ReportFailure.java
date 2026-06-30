package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A generic, feature-agnostic failure record (table {@code report_failures}). Each feature writes rows with its
 * own {@code type} discriminator and the id of the failing entity ({@code entityId}); e.g. Agent Insights uses
 * {@code type="agent_insights"} with the project id. Append-only — readers look at the latest row per entity.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReportFailure(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonIgnore String workspaceId,
        @NotBlank @Size(max = 100) String type,
        @NotNull UUID entityId,
        @NotBlank @Size(max = 255) String reason,
        String detail,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReportFailurePage(int page, int size, long total, List<ReportFailure> content) {
    }
}
