package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;
import lombok.Builder;

/** Only the vectors are read; the envelope's other fields are ignored. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedResponse(@Nullable EmbedResult result) {
}
