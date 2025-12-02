package com.comet.opik.api;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder(toBuilder = true)
public record ExperimentGroupAggregationItem(
        List<String> groupValues,
        Long experimentCount,
        Long traceCount,
        BigDecimal totalEstimatedCost,
        BigDecimal totalEstimatedCostAvg,
        PercentageValues duration, // p50, p90, p99 from DB
        List<FeedbackScoreAverage> feedbackScores, // name -> average value from DB
        List<FeedbackScoreAverage> experimentScores) { // name -> average value from DB
}
