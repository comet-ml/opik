package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.util.List;

/** One vector per input, in the inputs' order. Not checked here: a malformed answer reaches the caller as nulls. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedResult(@Nullable List<List<Float>> embeddings) {
}
