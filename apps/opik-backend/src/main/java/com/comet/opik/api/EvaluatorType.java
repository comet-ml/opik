package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum EvaluatorType {
    LLM_JUDGE("llm_judge"),
    CODE_METRIC("code_metric"),
    ;

    @JsonValue
    private final String value;

    @JsonCreator
    public static EvaluatorType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown evaluator type '%s'".formatted(value)));
    }
}
