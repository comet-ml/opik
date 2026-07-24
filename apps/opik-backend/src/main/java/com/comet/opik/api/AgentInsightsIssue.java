package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentInsightsIssue(
        UUID id,
        String name,
        String description,
        String cause,
        String suggestedFix,
        AgentInsightsIssueStatus status,
        AgentInsightsIssueSeverity severity,
        String tracesQuery,
        @Schema(description = "SUM(count) over the requested window") long totalOccurrences,
        @Schema(description = "Occurrences on the latest report day in the window only. The issue's description/cause "
                + "narrate that most recent run, so this is the count consistent with them; totalOccurrences instead "
                + "sums every day in the window.") long latestCount,
        @Schema(description = "SUM(total_count) over the requested window") long total,
        @Schema(description = "SUM(users_impacted) over the requested window") long usersImpacted,
        @Schema(description = "SUM(total_users) over the requested window") long totalUsers,
        @Schema(description = "MIN(report_day) in the requested window") LocalDate firstSeen,
        @Schema(description = "MAX(report_day) in the requested window") LocalDate lastSeen,
        @Schema(description = "COUNT(DISTINCT report_day) in the requested window") long daysReported,
        String createdBy,
        Instant createdAt,
        String lastUpdatedBy,
        Instant lastUpdatedAt) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AgentInsightsIssuePage(
            int page,
            int size,
            long total,
            List<AgentInsightsIssue> content) implements Page<AgentInsightsIssue> {

        public static AgentInsightsIssuePage empty(int page, int size) {
            return new AgentInsightsIssuePage(page, size, 0, List.of());
        }
    }
}
