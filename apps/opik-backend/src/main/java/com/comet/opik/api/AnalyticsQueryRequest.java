package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.UUID;

/**
 * A free-form analytics SQL request.
 *
 * <p>{@code projectId} belongs to the scope-in-the-body endpoint: supplying it restricts {@code traces} and
 * {@code spans} to that project, omitting it covers the whole workspace. Tables without a project dimension are
 * unaffected either way. The project-scoped endpoint takes its project from the path and rejects the field here,
 * so there is never a question of which one wins.
 *
 * <p>The workspace is never part of the request — it comes from the authenticated caller.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnalyticsQueryRequest(
        @Schema(description = "Read-only ClickHouse SQL. Must return exactly one column named `result` produced via toJSONString(...)") @NotBlank String query,
        @Schema(description = "Restrict traces and spans to this project. Omit to query the whole workspace. Not accepted by the project-scoped endpoint, which takes the project from the path.") UUID projectId) {
}
