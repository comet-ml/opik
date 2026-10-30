package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/** {@code usage} counts the inputs' tokens; {@code output_tokens} is 0. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedResponse(String requestId, Usage usage, boolean cacheHit, long latencyMs,
        EmbedResult result) {
}
