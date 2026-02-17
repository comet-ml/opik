package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum EvaluatorType {
    LLM_JUDGE("llm_judge"),
    CODE_METRIC("code_metric"),
    ;

    @JsonValue
    private final String value;

    public static Optional<EvaluatorType> fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }
}
