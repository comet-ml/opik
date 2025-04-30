package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum OptimizationStatus {
    RUNNING("running"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    @JsonValue
    private final String value;

    @JsonCreator
    public static OptimizationStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Optimization Status '%s'".formatted(value)));
    }
}
