package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.UUID;

/**
 * A free-form analytics SQL request that carries its own scope.
 *
 * <p>Supplying {@code projectId} restricts {@code traces} and {@code spans} to that project; omitting it covers the
 * whole workspace. Tables without a project dimension are unaffected either way. The workspace is never part of the
 * request — it comes from the authenticated caller.
 *
 * <p>Separate from {@link AnalyticsQueryRequest} rather than extending it, because records are final. Keeping them
 * apart is what lets the path-scoped endpoint publish a schema with no project field at all, instead of advertising
 * one it would have to reject. When that endpoint goes, this type takes over its name.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScopedAnalyticsQueryRequest(
        @Schema(description = "Read-only ClickHouse SQL. Must return exactly one column named `result` produced via toJSONString(...)") @NotBlank String query,
        @Schema(description = "Restrict traces and spans to this project. Omit to query the whole workspace.") UUID projectId) {
}
