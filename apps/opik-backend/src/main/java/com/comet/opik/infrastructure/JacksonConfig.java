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
}
