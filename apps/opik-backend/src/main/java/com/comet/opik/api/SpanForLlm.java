package com.comet.opik.api;

import com.comet.opik.domain.SpanType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * Lean projection of {@link Span} for the LLM-judge prompt and Python thread evaluator
 * conversation. The raw {@code Span} record carries ~28 fields — audit metadata, feedback
 * scores, comments, cost data, etc. — and pasting all of it into a prompt burns tokens on
 * irrelevant content and can confuse the judge ("why is there a {@code feedback_scores}
 * array? am I supposed to defer to that?").
 *
 * <p>Keep only what helps a judge reason about agent behavior:
 * <ul>
 *   <li>{@code name} / {@code type}: which tool or LLM ran
 *   <li>{@code input} / {@code output}: what was passed and what came back
 *   <li>{@code metadata}: user-provided context the agent attached
 *   <li>{@code startTime} / {@code endTime} / {@code duration}: call order + how long
 *   <li>{@code model} / {@code provider}: for LLM spans, which model
 *   <li>{@code errorInfo}: if the call failed
 *   <li>{@code spans}: nested child spans, recursively projected — lets the judge see the
 *       call tree (planner → sub-agent → tool) instead of a flat list with parent-id refs
 *       it would have to mentally resolve.
 * </ul>
 * Null fields are omitted via {@code @JsonInclude(NON_NULL)} so e.g. a successful tool
 * span doesn't pad the JSON with {@code error_info: null}, and a leaf span doesn't pad
 * with an empty {@code spans} array.
 *
 * <p>Lives in the {@code api} package alongside {@link Span} so both the LLM-render path
 * ({@code OnlineScoringEngine}) and the Python-thread-render path
 * ({@code TraceThreadPythonEvaluatorRequest.ChatMessage}) can reference it without a
 * package cycle.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder(toBuilder = true)
public record SpanForLlm(
        String name,
        SpanType type,
        Instant startTime,
        Instant endTime,
        Double duration,
        JsonNode input,
        JsonNode output,
        JsonNode metadata,
        String model,
        String provider,
        ErrorInfo errorInfo,
        List<SpanForLlm> spans) {
}
