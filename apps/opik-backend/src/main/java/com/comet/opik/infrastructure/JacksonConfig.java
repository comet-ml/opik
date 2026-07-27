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

    // Upper bound for the byte caps below: a single Java String/array can't exceed Integer.MAX_VALUE
    // (2^31-1 bytes), so a larger cap can't protect the heap and is almost always a units typo.
    private static final long MAX_CONFIGURABLE_BYTES = Integer.MAX_VALUE; // ~2GB (2^31-1)

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
     * Maximum decompressed document size (whole batch), enforced mid-parse -> 413; catches a batch of many
     * small values that {@link #maxStringLength} misses. {@code <= 0} = unlimited (hence the custom
     * {@link #isMaxDocumentLengthValid()} instead of {@code @Min}/{@code @Max}).
     */
    @JsonProperty
    private long maxDocumentLength;

    /**
     * Maximum request body size (compressed {@code Content-Length}), rejected 413 pre-parse by
     * {@link com.comet.opik.infrastructure.RequestSizeLimitFilter} - a different axis from
     * {@link #maxDocumentLength} (decompressed), so a lower value is legitimate.
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxRequestSizeBytes must be at least 1MB") @Max(value = MAX_CONFIGURABLE_BYTES, message = "maxRequestSizeBytes must be at most 2GB") private long maxRequestSizeBytes;

    @JsonIgnore
    @AssertTrue(message = "maxDocumentLength must be <= 0 (unlimited) or between maxStringLength and 2GB")
    public boolean isMaxDocumentLengthValid() {
        return maxDocumentLength <= 0
                || (maxDocumentLength >= maxStringLength && maxDocumentLength <= MAX_CONFIGURABLE_BYTES);
    }

    // No cross-field check of maxRequestSizeBytes vs maxDocumentLength: different axes (compressed vs
    // decompressed), so request < document is legitimate (config-test.yml relies on it for the 413 path).
}
