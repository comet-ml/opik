package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.UUID;

/**
 * A Custom Charts query. Unlike {@link AnalyticsQueryRequest} the project is part of the request rather than the
 * path, and is optional: omitting it scopes the query to the whole workspace, which is what a chart over datasets or
 * experiments needs. The workspace is never part of the request — it comes from the authenticated caller.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChartQueryRequest(
        @Schema(description = "Read-only ClickHouse SQL. Must return exactly one column named `result` produced via toJSONString(...)") @NotBlank String query,
        @Schema(description = "Restrict traces and spans to this project. Omit to query the whole workspace; tables without a project dimension are unaffected either way.") UUID projectId) {
}
