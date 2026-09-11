package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AutomationRuleEvaluatorType {

    LLM_AS_JUDGE(Constants.LLM_AS_JUDGE),
    USER_DEFINED_METRIC_PYTHON(Constants.USER_DEFINED_METRIC_PYTHON),
    TRACE_THREAD_LLM_AS_JUDGE(Constants.TRACE_THREAD_LLM_AS_JUDGE),
    TRACE_THREAD_USER_DEFINED_METRIC_PYTHON(Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON),
    SPAN_LLM_AS_JUDGE(Constants.SPAN_LLM_AS_JUDGE),
    SPAN_USER_DEFINED_METRIC_PYTHON(Constants.SPAN_USER_DEFINED_METRIC_PYTHON);

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
        public static final String USER_DEFINED_METRIC_PYTHON = "user_defined_metric_python";
        public static final String TRACE_THREAD_LLM_AS_JUDGE = "trace_thread_llm_as_judge";
        public static final String TRACE_THREAD_USER_DEFINED_METRIC_PYTHON = "trace_thread_user_defined_metric_python";
        public static final String SPAN_LLM_AS_JUDGE = "span_llm_as_judge";
        public static final String SPAN_USER_DEFINED_METRIC_PYTHON = "span_user_defined_metric_python";
    }
}
