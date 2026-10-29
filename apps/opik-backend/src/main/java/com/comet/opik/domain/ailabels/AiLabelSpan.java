package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.SpanType;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.util.List;

/**
 * One node of the span tree a trace renders to. ai-labels' own copy of {@link com.comet.opik.api.SpanForLlm}, not a
 * shared type: the rendering is expected to change, and online scoring's judge prompts must not change with it.
 *
 * <p>{@code type} isn't rendered; it's counted in {@code spans_summary}. Null fields and an empty {@code spans} are
 * left out of the state.
 */
@Builder(toBuilder = true)
public record AiLabelSpan(
        String name,
        SpanType type,
        JsonNode input,
        JsonNode output,
        List<AiLabelSpan> spans) {
}
