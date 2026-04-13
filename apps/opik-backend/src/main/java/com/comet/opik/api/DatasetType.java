package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum DatasetType {
    DATASET("dataset"),
    // TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
    TEST_SUITE("evaluation_suite");

    @JsonValue
    private final String value;

    public static Optional<DatasetType> fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }
}
