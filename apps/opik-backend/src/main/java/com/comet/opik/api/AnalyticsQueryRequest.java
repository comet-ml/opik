package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnalyticsQueryRequest(
        @NotNull UUID projectId,
        @Schema(description = "Read-only ClickHouse SQL. Must return exactly one column named `result` produced via toJSONString(...)") @NotBlank String query) {
}
