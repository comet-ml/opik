package com.comet.opik.domain.experiments.aggregations;

import lombok.Builder;

import java.util.Map;
import java.util.UUID;

/**
 * Models for aggregated experiment metrics.
 * These records represent computed statistics across multiple traces/spans.
 */
public class ExperimentAggregatesModel {

    /**
     * Aggregated trace-level metrics for an experiment.
     *
     * @param experimentId       The experiment ID
     * @param projectId          The project ID
     * @param durationPercentiles Percentile values for trace durations (p50, p90, p99)
     * @param traceCount         Total number of traces in the experiment
     */
    @Builder
    public record TraceAggregations(
            UUID experimentId,
            UUID projectId,
            Map<String, Double> durationPercentiles,
            long traceCount) {
    }

    /**
     * Aggregated span-level metrics for an experiment.
     *
     * @param experimentId                    The experiment ID
     * @param usageAvg                        Average token usage by type
     * @param totalEstimatedCostSum           Sum of estimated costs
     * @param totalEstimatedCostAvg           Average estimated cost
     * @param totalEstimatedCostPercentiles   Percentile values for costs (p50, p90, p99)
     * @param usageTotalTokensPercentiles     Percentile values for total token usage
     */
    @Builder
    public record SpanAggregations(
            UUID experimentId,
            Map<String, Double> usageAvg,
            double totalEstimatedCostSum,
            double totalEstimatedCostAvg,
            Map<String, Double> totalEstimatedCostPercentiles,
            Map<String, Double> usageTotalTokensPercentiles) {
    }

    /**
     * Aggregated feedback score metrics for an experiment.
     *
     * @param experimentId              The experiment ID
     * @param feedbackScoresPercentiles Percentile values for each feedback score (p50, p90, p99)
     * @param feedbackScoresAvg         Average value for each feedback score
     */
    @Builder
    public record FeedbackScoreAggregations(
            UUID experimentId,
            Map<String, Map<String, Double>> feedbackScoresPercentiles,
            Map<String, Double> feedbackScoresAvg) {
    }
}
