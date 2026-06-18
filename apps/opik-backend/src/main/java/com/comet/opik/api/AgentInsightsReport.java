package com.comet.opik.api;

import com.comet.opik.api.validation.MaxJsonSize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentInsightsReport(
        @NotNull UUID projectId,
        @NotNull LocalDate reportDay,
        @NotEmpty List<@NotNull @Valid ReportedIssue> issues) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReportedIssue(
            UUID id,
            @NotBlank @Size(max = 255) String name,
            String description,
            String cause,
            String suggestedFix,
            String tracesQuery,
            @NotNull @PositiveOrZero Long count,
            @NotNull @PositiveOrZero Long totalCount,
            @NotNull @PositiveOrZero Long usersImpacted,
            @NotNull @PositiveOrZero Long totalUsers,
            // 65,535 = the metadata TEXT column's byte limit; reject oversized payloads at the boundary
            @MaxJsonSize(65_535) JsonNode metadata) {
    }
}
