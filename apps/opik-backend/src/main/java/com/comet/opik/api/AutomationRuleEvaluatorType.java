package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AutomationRuleEvaluatorType {

    LLM_AS_JUDGE(Constants.LLM_AS_JUDGE);

    @JsonValue
    private final String type;

    public static AutomationRuleEvaluatorType fromString(String type) {
        return Arrays.stream(values())
                .filter(v -> v.type.equals(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown evaluator type: " + type));
    }

    @UtilityClass
    public static class Constants {
        public static final String LLM_AS_JUDGE = "llm_as_judge";
    }
}
