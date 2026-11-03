package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code code} stays a string so an item failing with a code this client doesn't know yet still parses; see
 * {@link InferenceErrorCode} for the known ones.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorBody(@NonNull String code, String message, boolean retryable) {
}
