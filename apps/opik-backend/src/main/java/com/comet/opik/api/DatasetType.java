package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum DatasetType {
    DATASET("dataset"),
    EVALUATION_SUITE("evaluation_suite");

    @JsonValue
    private final String value;

    @JsonCreator
    public static DatasetType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(DATASET);
    }
}
