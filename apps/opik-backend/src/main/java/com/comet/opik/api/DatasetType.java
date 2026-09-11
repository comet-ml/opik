package com.comet.opik.api;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum DatasetType implements HasValue {
    DATASET("dataset"),
    // TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
    TEST_SUITE("evaluation_suite");

    @JsonValue
    private final String value;

    @JsonCreator
    public static DatasetType fromValue(String value) {
        return fromString(value)
                .orElseThrow(() -> new IllegalArgumentException("Unknown DatasetType value: " + value));
    }

    public static Optional<DatasetType> fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }
}
