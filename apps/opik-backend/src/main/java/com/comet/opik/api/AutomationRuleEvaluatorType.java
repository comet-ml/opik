package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AutomationRuleEvaluatorType {

    LLM_AS_JUDGE("llm_as_judge");

    @JsonValue
    private final String type;

    public static AutomationRuleEvaluatorType fromString(String type) {
        return Arrays.stream(values())
                .filter(v -> v.type.equals(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown evaluator type: " + type));
    }
}
