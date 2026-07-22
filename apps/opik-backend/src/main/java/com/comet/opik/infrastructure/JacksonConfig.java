package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadConstraints;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Jackson JSON processing configuration.
 *
 * Controls limits for JSON deserialization to prevent memory exhaustion
 * from extremely large payloads (e.g., base64-encoded attachments).
 */
@Data
@NoArgsConstructor
public class JacksonConfig {

    // Upper bound for the externally-configured byte caps below: beyond ~2GB a single Java String/array
    // hits its 2^31 limit, so a larger cap can't protect the heap and is almost always a units typo.
    private static final long MAX_CONFIGURABLE_BYTES = 2_147_483_648L; // 2GB

    /**
     * Maximum size (in bytes) for individual string values during JSON deserialization.
     * Applies to BOTH HTTP requests and internal JSON processing for consistency.
     *
     * This ensures that if HTTP layer accepts a payload, internal processing can handle it.
     * Attachment stripping happens asynchronously after initial ingestion.
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxStringLength must be at least 1MB") private int maxStringLength = StreamReadConstraints.DEFAULT_MAX_STRING_LEN;

    /**
     * Maximum decompressed JSON document size (whole batch), enforced mid-parse -> 413; catches an
     * oversized batch of many small values that {@link #maxStringLength} misses. {@code <= 0} means
     * unlimited; positive values are bounds-checked by {@link #isMaxDocumentLengthValid()} (plain
     * {@code @Min}/{@code @Max} can't express the unlimited escape hatch). Configurable via
     * {@code JACKSON_MAX_DOCUMENT_LENGTH} (defaults differ by deployment).
     */
    @JsonProperty
    private long maxDocumentLength = 536_870_912L; // 512MB

    /**
     * Maximum incoming request body size (on-the-wire/compressed {@code Content-Length}), rejected 413
     * pre-parse by {@link com.comet.opik.infrastructure.RequestSizeLimitFilter}. A different axis from
     * {@link #maxDocumentLength} (decompressed), so a lower value is legitimate (see the note below).
     * Configurable via {@code JACKSON_MAX_REQUEST_SIZE_BYTES} (defaults differ by deployment).
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxRequestSizeBytes must be at least 1MB") @Max(value = MAX_CONFIGURABLE_BYTES, message = "maxRequestSizeBytes must be at most 2GB") private long maxRequestSizeBytes = 536_870_912L; // 512MB

    /**
     * {@code <= 0} (unlimited) is always valid; a positive value must be in
     * {@code [maxStringLength, MAX_CONFIGURABLE_BYTES]}. Enforced at startup ({@code @Valid}).
     */
    @JsonIgnore
    @AssertTrue(message = "maxDocumentLength must be <= 0 (unlimited) or between maxStringLength and 2GB")
    public boolean isMaxDocumentLengthValid() {
        return maxDocumentLength <= 0
                || (maxDocumentLength >= maxStringLength && maxDocumentLength <= MAX_CONFIGURABLE_BYTES);
    }

    // No cross-field check of maxRequestSizeBytes vs maxDocumentLength: different axes (compressed
    // request vs decompressed document), so request < document is legitimate - config-test.yml relies
    // on it (a lower request cap than the document cap) to exercise the 413 path cheaply.
}
