package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum OptimizationStatus {
    RUNNING("running", false),
    COMPLETED("completed", true),
    CANCELLED("cancelled", true),
    INITIALIZED("initialized", false),
    ERROR("error", true);

    @JsonValue
    private final String value;

    /**
     * Indicates whether this status represents a terminal (final) state.
     * Terminal statuses trigger log finalization and cleanup.
     */
    private final boolean terminal;

    @JsonCreator
    public static OptimizationStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Optimization Status '%s'".formatted(value)));
    }
}
