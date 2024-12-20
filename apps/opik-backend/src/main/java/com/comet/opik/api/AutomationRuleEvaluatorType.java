package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AutomationRuleEvaluatorType {

    LLM_AS_JUDGE("llm-as-judge"),
    PYTHON("python");

    @JsonValue
    private final String type;
}
