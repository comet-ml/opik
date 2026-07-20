package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadConstraints;
import jakarta.validation.constraints.AssertTrue;
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
     * decompressed document, so it also catches an oversized batch assembled from many small
     * values. Enforced mid-parse on both the HTTP and internal ObjectMappers, aborting with a 4xx
     * (via JsonProcessingExceptionMapper) before a multi-GB node tree is materialized.
     *
     * Must stay above {@link #maxStringLength} so a single legitimate max-size string still parses.
     *
     * Default 512MB is the self-hosted value; the Comet cloud deployment overrides it to 256MB via
     * {@code JACKSON_MAX_DOCUMENT_LENGTH}.
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxDocumentLength must be at least 1MB") private long maxDocumentLength = 536_870_912L; // 512MB

    /**
     * Maximum size (in bytes) of an incoming request body, read from the {@code Content-Length}
     * header before the body is consumed.
     *
     * Requests exceeding this are rejected with 413 pre-parse by
     * {@link com.comet.opik.infrastructure.RequestSizeLimitFilter}. Requests without a
     * Content-Length (e.g. chunked transfer) fall through and are bounded by
     * {@link #maxDocumentLength} during parsing.
     *
     * Kept equal to {@link #maxDocumentLength} (512MB self-hosted, 256MB on cloud via
     * {@code JACKSON_MAX_REQUEST_SIZE_BYTES}) so the filter never rejects a request the document
     * guard would otherwise accept.
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxRequestSizeBytes must be at least 1MB") private long maxRequestSizeBytes = 536_870_912L; // 512MB

    /**
     * Cross-field invariant: the whole-document cap must not be smaller than the single-string cap,
     * otherwise a legitimate max-size string that already passed {@link #maxStringLength} would be
     * rejected by the document guard. A non-positive {@code maxDocumentLength} means "unlimited" and
     * is always valid. Validated at startup (the config is {@code @Valid}), so a misordered override
     * such as {@code JACKSON_MAX_DOCUMENT_LENGTH < JACKSON_MAX_STRING_LENGTH} fails fast with a clear
     * message instead of surfacing as a confusing runtime rejection.
     */
    @JsonIgnore
    @AssertTrue(message = "maxDocumentLength must be >= maxStringLength (or <= 0 for unlimited)")
    public boolean isMaxDocumentLengthValid() {
        return maxDocumentLength <= 0 || maxDocumentLength >= maxStringLength;
    }

    /**
     * Cross-field invariant: the request-size cap (checked against {@code Content-Length}) must not be
     * smaller than the whole-document cap, otherwise {@link
     * com.comet.opik.infrastructure.RequestSizeLimitFilter} would reject a request with {@code 413}
     * that the document guard would have accepted. When {@code maxDocumentLength <= 0} (unlimited)
     * there is nothing to shadow, so any request cap is valid. Validated at startup alongside {@link
     * #isMaxDocumentLengthValid()}.
     */
    @JsonIgnore
    @AssertTrue(message = "maxRequestSizeBytes must be >= maxDocumentLength (unless maxDocumentLength <= 0 for unlimited)")
    public boolean isMaxRequestSizeValid() {
        return maxDocumentLength <= 0 || maxRequestSizeBytes >= maxDocumentLength;
    }
}
