package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.api.VisibilityMode;
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
     * @param traceId         The trace ID
     * @param projectId       The project ID
     * @param duration        The trace duration in milliseconds
     * @param metadata        The trace metadata (JSON)
     * @param input           The trace input
     * @param output          The trace output
     * @param inputSlim       The truncated trace input
     * @param outputSlim      The truncated trace output
     * @param visibilityMode  The trace visibility mode
     */
    @Builder
    public record TraceData(
            UUID traceId,
            UUID projectId,
            BigDecimal duration,
            String metadata,
            String input,
            String output,
            String inputSlim,
            String outputSlim,
            VisibilityMode visibilityMode) {
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
     * @param traceId              The trace ID
     * @param feedbackScores       Map of feedback score names to values (for aggregations)
     * @param feedbackScoresArray  JSON string of complete feedback score array
     */
    @Builder
    public record FeedbackScoreData(
            UUID traceId,
            Map<String, BigDecimal> feedbackScores,
            String feedbackScoresArray) {
    }

    /**
     * Raw comments data for aggregation.
     *
     * @param traceId           The trace ID
     * @param commentsArrayAgg  JSON string of comments array for this trace
     */
    @Builder
    public record CommentsData(
            UUID traceId,
            String commentsArrayAgg) {
    }
}
