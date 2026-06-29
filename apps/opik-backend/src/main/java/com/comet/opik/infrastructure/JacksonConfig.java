package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadConstraints;
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
     * Maximum total length (in bytes) of a single JSON document during deserialization.
     *
     * Unlike {@link #maxStringLength} (which caps an individual string value), this caps the
     * whole document, so it catches payloads made of millions of small nodes that would
     * otherwise expand into a multi-GB Jackson tree before any other guard fires. The limit is
     * enforced by the streaming parser, so an oversized document fails fast and the tree is
     * never materialized.
     *
     * Sizing rule: the cap must sit above the largest legitimate payload (multi-field documents and
     * multi-attachment batches can total a few hundred MB) yet far below the multi-GB trees that
     * caused the OOM, while keeping {@code maxConcurrentRequests * maxDocumentLength} within the
     * ~11.2GB heap (70% of 16Gi). 512MB clears the documented payloads with room to spare and still
     * rejects the pathological gigabyte-scale documents.
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxDocumentLength must be at least 1MB") private long maxDocumentLength = 536870912L;

    /**
     * Maximum size (in bytes) of a request body, enforced from the {@code Content-Length} header
     * before the body is read. Bodies above this are rejected with 413, fast-failing oversized
     * ingestion at the request boundary so the parser never even starts.
     *
     * This is a pre-parse complement to {@link #maxDocumentLength}: the header reflects the
     * on-the-wire (possibly gzip-compressed) size, so requests without a Content-Length (e.g.
     * chunked) or with a small-but-amplifying compressed body still rely on maxDocumentLength,
     * which is enforced on the decompressed stream during parsing.
     *
     * Set at or above maxDocumentLength so legitimate documents at the document-length limit are not
     * rejected by their (slightly larger) request envelope.
     */
    @JsonProperty
    @Min(value = 1048576, message = "maxRequestSizeBytes must be at least 1MB") private long maxRequestSizeBytes = 536870912L;
}
