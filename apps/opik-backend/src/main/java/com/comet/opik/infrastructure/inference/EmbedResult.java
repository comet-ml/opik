package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * One vector per input, in the inputs' order, each {@code dimensions} long. {@link InferenceClient#embed} checks all of
 * it, and fails an answer that breaks it as {@code upstream_error}.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedResult(float[][] embeddings, int dimensions) {
}
