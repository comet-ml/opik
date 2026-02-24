package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum EvaluationMethod {
    DATASET("dataset"),
    EVALUATION_SUITE("evaluation_suite");

    @JsonValue
    private final String value;

    public static Optional<EvaluationMethod> fromString(String value) {
        return Arrays.stream(values())
                .filter(method -> method.value.equals(value))
                .findFirst();
    }
}
