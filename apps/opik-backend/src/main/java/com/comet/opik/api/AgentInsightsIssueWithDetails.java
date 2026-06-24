package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentInsightsIssueWithDetails(
        UUID id,
        String name,
        String description,
        String cause,
        String suggestedFix,
        AgentInsightsIssueStatus status,
        AgentInsightsIssueSeverity severity,
        String tracesQuery,
        String createdBy,
        Instant createdAt,
        String lastUpdatedBy,
        Instant lastUpdatedAt,
        @Schema(description = "Per-day breakdown within the requested window, ordered by report_day ascending") List<AgentInsightsIssueDetail> details) {
}
