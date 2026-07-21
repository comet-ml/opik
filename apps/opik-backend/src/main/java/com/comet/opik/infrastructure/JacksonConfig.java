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

    /**
     * Upper bound for the externally-configured byte limits below. Beyond ~2GB a single Java
     * String/array hits its {@code Integer.MAX_VALUE} (2^31) size limit, so a document/request cap
     * larger than this can no longer protect the heap and is almost always a units typo (e.g. bytes
     * entered where MB were meant). Used to fail such a misconfiguration fast at startup.
     */
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
     * Maximum size (in bytes) of an entire JSON document during deserialization.
     *
     * Unlike {@link #maxStringLength} (which bounds a single string value), this caps the whole
     * decompressed document, so it also catches an oversized batch assembled from many small values.
     * Enforced mid-parse on stream-based reads - notably the HTTP request body - aborting with a 413
     * (via JsonProcessingExceptionMapper) before a multi-GB node tree is materialized. Jackson checks
     * this only on a parser buffer refill, so a fully in-memory {@code String}/{@code byte[]} read
     * that fits a single buffer is NOT re-checked by it; those inputs were already bounded at
     * ingestion.
     *
     * Cannot use plain {@code @Min}/{@code @Max}: a non-positive value is a deliberate "unlimited"
     * escape hatch, so both bounds are enforced by {@link #isMaxDocumentLengthValid()} instead.
     *
     * Default 512MB is the self-hosted value; the Comet cloud deployment overrides it via
     * {@code JACKSON_MAX_DOCUMENT_LENGTH}.
     */
    @JsonProperty
    private long maxDocumentLength = 536_870_912L; // 512MB

    /**
     * Maximum size (in bytes) of an incoming request body, read from the {@code Content-Length}
     * header before the body is consumed.
     *
     * Requests exceeding this are rejected with 413 pre-parse by
     * {@link com.comet.opik.infrastructure.RequestSizeLimitFilter}. Requests without a Content-Length
     * (e.g. chunked transfer) fall through and are bounded by {@link #maxDocumentLength} during parsing.
     *
     * This bounds the on-the-wire (compressed) size - a different axis from {@link #maxDocumentLength}
     * (decompressed) - so a request cap below the document cap is legitimate (see the note below).
     * Default 512MB is the self-hosted value; the Comet cloud deployment overrides it via
     * {@code JACKSON_MAX_REQUEST_SIZE_BYTES}.
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxRequestSizeBytes must be at least 1MB") @Max(value = MAX_CONFIGURABLE_BYTES, message = "maxRequestSizeBytes must be at most 2GB") private long maxRequestSizeBytes = 536_870_912L; // 512MB

    /**
     * Validates {@link #maxDocumentLength}, which cannot use plain {@code @Min}/{@code @Max} because a
     * non-positive value is a deliberate "unlimited" escape hatch. A non-positive value is always
     * valid (unlimited); any positive value must be {@code >=} {@link #maxStringLength} (so a single
     * legitimate max-size string still parses) and {@code <=} 2GB (see {@link #MAX_CONFIGURABLE_BYTES}).
     * Validated at startup (the config is {@code @Valid}), so a misconfigured override fails fast with
     * a clear message instead of a confusing runtime rejection or a silently disabled guard.
     */
    @JsonIgnore
    @AssertTrue(message = "maxDocumentLength must be <= 0 (unlimited) or between maxStringLength and 2GB")
    public boolean isMaxDocumentLengthValid() {
        return maxDocumentLength <= 0
                || (maxDocumentLength >= maxStringLength && maxDocumentLength <= MAX_CONFIGURABLE_BYTES);
    }

    // NOTE: intentionally no cross-field check of maxRequestSizeBytes against maxDocumentLength.
    // They measure different axes - maxRequestSizeBytes bounds the on-the-wire (compressed)
    // Content-Length, while maxDocumentLength bounds the decompressed document - so a request cap
    // below the document cap is a legitimate configuration (e.g. cap uploads at the edge while still
    // parsing larger decompressed batches), not a misconfiguration. config-test.yml relies on exactly
    // that (50MB request < 256MB document) to exercise the 413 path cheaply.
}
