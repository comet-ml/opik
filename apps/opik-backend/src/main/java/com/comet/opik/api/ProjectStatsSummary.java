package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectStatsSummary(List<ProjectStatsSummaryItem> content) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProjectStatsSummaryItem(
            UUID projectId,
            List<FeedbackScoreAverage> feedbackScores,
            PercentageValues duration,
            Double totalEstimatedCost,
            Double totalEstimatedCostSum,
            Map<String, Double> usage,
            Map<String, Double> usageSum,
            Long traceCount,
            Long threadCount,
            Long guardrailsFailedCount,
            ErrorCountWithDeviation errorCount) {
    }

}
