package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@RequiredArgsConstructor
public enum DatasetType {
    DATASET("dataset"),
    EVALUATION_SUITE("evaluation_suite");

    @JsonValue
    private final String value;

    @JsonCreator
    public static DatasetType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown dataset type '%s'".formatted(value)));
    }
}
