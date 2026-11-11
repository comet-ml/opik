package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum JobStatus {
    PENDING("pending", false),
    RUNNING("running", false),
    COMPLETED("completed", true),
    PARTIALLY_FAILED("partially_failed", true),
    CANCELLED("cancelled", true);

    @JsonValue
    private final String value;
    private final boolean terminal;
}
