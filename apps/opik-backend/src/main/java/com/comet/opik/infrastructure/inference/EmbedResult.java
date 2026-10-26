package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** One vector per input, in the inputs' order. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedResult(@NonNull List<List<Float>> embeddings, int dimensions) {
}
