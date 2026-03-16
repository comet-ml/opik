package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RunStatus {
    PASSED("passed"),
    FAILED("failed");

    @JsonValue
    private final String value;
}
