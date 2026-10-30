package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.util.List;

/** One vector per input, in the inputs' order. Not checked here: a malformed answer reaches the caller as nulls. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedResult(List<List<Float>> embeddings, int dimensions) {
}
