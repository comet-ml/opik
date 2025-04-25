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
            Map<String, Double> usage,
            Long traceCount,
            Long guardrailsFailedCount) {
    }

}
