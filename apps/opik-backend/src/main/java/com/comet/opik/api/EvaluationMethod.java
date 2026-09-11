package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum EvaluationMethod {
    DATASET("dataset"),
    // TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
    TEST_SUITE("evaluation_suite");

    public static final String UNKNOWN_VALUE = "unknown";

    @JsonValue
    private final String value;

    @JsonCreator
    public static EvaluationMethod fromValue(String value) {
        return fromString(value)
                .orElseThrow(() -> new IllegalArgumentException("Unknown EvaluationMethod value: " + value));
    }

    public static Optional<EvaluationMethod> fromString(String value) {
        return Arrays.stream(values())
                .filter(method -> method.value.equals(value))
                .findFirst();
    }
}
