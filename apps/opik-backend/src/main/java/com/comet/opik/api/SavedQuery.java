package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * A query Ollie pinned to a chart. An {@code ollie_chart} dashboard widget references it by id and runs it over the
 * dashboard's date range, through {@code {{window_start}}} / {@code {{window_end}}}.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SavedQuery(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @Nullable @Schema(description = "Project the query is bounded to. Omit for every project in the workspace.") UUID projectId,
        // Well above any generated query, and below ClickHouse's 256 KiB max_query_size.
        @NotBlank @Size(max = 65_536) String sql,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy) {
}
