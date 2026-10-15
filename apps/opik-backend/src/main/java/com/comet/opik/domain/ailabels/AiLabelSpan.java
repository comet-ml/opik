package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.SpanType;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * One node of the span tree a trace renders to. ai-labels' own copy of {@link com.comet.opik.api.SpanForLlm}, not a
 * shared type: the rendering is expected to change, and online scoring's judge prompts must not change with it.
 *
 * <p>{@code errorInfo} is kept as stored, rather than as {@link com.comet.opik.api.ErrorInfo}, so a value that does not
 * bind to that record still renders. Null fields and an empty {@code spans} are left out of the state.
 */
@Builder(toBuilder = true)
public record AiLabelSpan(
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
        JsonNode errorInfo,
        List<AiLabelSpan> spans) {
}
