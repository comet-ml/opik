package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

/** One LLM call an algorithm asks for. The run makes it, with the deadline of its {@code call}. */
@Builder(toBuilder = true)
public record LlmRequest(@NonNull LlmCall call, @NonNull String prompt, @NonNull JsonNode schema, int maxTokens) {
}
