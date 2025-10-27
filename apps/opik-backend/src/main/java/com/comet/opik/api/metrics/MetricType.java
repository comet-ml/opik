package com.comet.opik.api.metrics;

public enum MetricType {
    // Trace metrics
    FEEDBACK_SCORES,
    TRACE_COUNT,
    TOKEN_USAGE,
    DURATION,
    COST,
    GUARDRAILS_FAILED_COUNT,
    ERROR_COUNT,
    COMPLETION_TOKENS,
    PROMPT_TOKENS,
    TOTAL_TOKENS,
    INPUT_COUNT,
    OUTPUT_COUNT,
    METADATA_COUNT,
    TAGS_AVERAGE,
    TRACE_WITH_ERRORS_PERCENT,
    GUARDRAILS_PASS_RATE,
    AVG_COST_PER_TRACE,

    // Span metrics (aggregated at trace level)
    SPAN_COUNT, // Average spans per trace
    LLM_SPAN_COUNT, // Average LLM spans per trace
    SPAN_DURATION, // Span duration percentiles

    // Span metrics (direct span-level metrics)
    SPAN_TOTAL_COUNT, // Total number of spans
    SPAN_ERROR_COUNT, // Spans with errors
    SPAN_INPUT_COUNT, // Spans with input
    SPAN_OUTPUT_COUNT, // Spans with output
    SPAN_METADATA_COUNT, // Spans with metadata
    SPAN_TAGS_AVERAGE, // Average tags per span
    SPAN_COST, // Total span cost
    SPAN_AVG_COST, // Average cost per span
    SPAN_FEEDBACK_SCORES, // Span feedback scores
    SPAN_TOKEN_USAGE, // All span token types
    SPAN_PROMPT_TOKENS, // Span prompt tokens
    SPAN_COMPLETION_TOKENS, // Span completion tokens
    SPAN_TOTAL_TOKENS, // Span total tokens

    // Thread metrics
    THREAD_COUNT,
    THREAD_DURATION,
    THREAD_FEEDBACK_SCORES,
}
