package com.comet.opik.api.metrics;

public enum MetricType {
    // Existing metrics
    FEEDBACK_SCORES,
    TRACE_COUNT,
    TOKEN_USAGE,
    DURATION,
    COST,
    GUARDRAILS_FAILED_COUNT,
    THREAD_COUNT,
    THREAD_DURATION,
    THREAD_FEEDBACK_SCORES,

    // Easy additions - data already aggregated
    ERROR_COUNT,
    SPAN_COUNT,
    LLM_SPAN_COUNT,

    // Medium additions - token metrics
    COMPLETION_TOKENS,
    PROMPT_TOKENS,
    TOTAL_TOKENS,

    // Medium additions - count metrics
    INPUT_COUNT,
    OUTPUT_COUNT,
    METADATA_COUNT,
    TAGS_AVERAGE,

    // Medium additions - calculated metrics
    TRACE_WITH_ERRORS_PERCENT,
    GUARDRAILS_PASS_RATE,
    AVG_COST_PER_TRACE,

    // Medium additions - span duration
    SPAN_DURATION,
}
