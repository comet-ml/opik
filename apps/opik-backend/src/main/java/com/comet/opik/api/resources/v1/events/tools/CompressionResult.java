package com.comet.opik.api.resources.v1.events.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

/**
 * Output of {@link EntityCompressor}. {@code tier} reflects what was actually
 * produced — for adaptive compressors the auto-pick can differ from the
 * requested tier; for fixed-tier compressors it ignores the request entirely.
 */
@Builder(toBuilder = true)
public record CompressionResult(@NonNull JsonNode payload, @NonNull CompressionTier tier) {
}