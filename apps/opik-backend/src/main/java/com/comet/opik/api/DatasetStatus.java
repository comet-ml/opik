package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum DatasetStatus {
    UNKNOWN("unknown"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed");

    @JsonValue
    private final String value;

    @JsonCreator
    public static DatasetStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
