package com.comet.opik.domain.experiments.aggregations;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Source data models from traces and spans.
 * These records represent raw data before aggregation.
 */
public class ExperimentSourceData {

    /**
     * Raw trace data for aggregation.
     *
     * @param traceId   The trace ID
     * @param projectId The project ID
     * @param duration  The trace duration in milliseconds
     */
    @Builder
    public record TraceData(
            UUID traceId,
            UUID projectId,
            BigDecimal duration) {
    }

    /**
     * Raw span data for aggregation.
     *
     * @param traceId            The trace ID
     * @param usage              Token usage by type (input, output, total)
     * @param totalEstimatedCost The estimated cost
     */
    @Builder
    public record SpanData(
            UUID traceId,
            Map<String, Long> usage,
            BigDecimal totalEstimatedCost) {
    }

    /**
     * Raw feedback score data for aggregation.
     *
     * @param traceId        The trace ID
     * @param feedbackScores Map of feedback score names to values
     */
    @Builder
    public record FeedbackScoreData(
            UUID traceId,
            Map<String, BigDecimal> feedbackScores) {
    }
}
