package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.UUID;

/**
 * A free-form analytics SQL request that carries its own scope.
 *
 * <p>Supplying {@code projectId} restricts the query to that project; omitting it covers the
 * whole workspace. Tables without a project dimension are unaffected either way.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScopedAnalyticsQueryRequest(
        @Schema(description = "Read-only ClickHouse SQL. Must return exactly one column named `result` produced via toJSONString(...)") @NotBlank String query,
        @Nullable @Schema(description = "Restrict query to this project. Omit to query the whole workspace.") UUID projectId) {
}
