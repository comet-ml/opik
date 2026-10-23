package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * Error envelope of the inference service:
 * {@code {"error": {"code": "deadline_exceeded", "message": "...", "retryable": true}}}.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
record DecideErrorResponse(Error error) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(String code, String message, Boolean retryable) {
    }
}
