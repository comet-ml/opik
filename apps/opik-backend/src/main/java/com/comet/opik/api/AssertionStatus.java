package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum AssertionStatus {
    PASSED("passed"),
    FAILED("failed");

    public static final String UNKNOWN_VALUE = "unknown";

    @JsonValue
    private final String value;

    public static Optional<AssertionStatus> fromString(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst();
    }
}
