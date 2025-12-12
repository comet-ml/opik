package com.comet.opik.api.evaluators;

import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorModel;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorWithProjectRowMapper;
import com.comet.opik.domain.evaluators.LlmAsJudgeAutomationRuleEvaluatorModel;
import com.comet.opik.domain.evaluators.RowMapperFactory;
import com.comet.opik.domain.evaluators.SpanLlmAsJudgeAutomationRuleEvaluatorModel;
import com.comet.opik.domain.evaluators.SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel;
import com.comet.opik.domain.evaluators.TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel;
import com.comet.opik.domain.evaluators.TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel;
import com.comet.opik.domain.evaluators.UserDefinedMetricPythonAutomationRuleEvaluatorModel;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.UtilityClass;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum AutomationRuleEvaluatorType {

    LLM_AS_JUDGE(Constants.LLM_AS_JUDGE,
            LlmAsJudgeAutomationRuleEvaluatorModel.class,
            LlmAsJudgeAutomationRuleEvaluatorModel::fromRowMapper),

    USER_DEFINED_METRIC_PYTHON(Constants.USER_DEFINED_METRIC_PYTHON,
            UserDefinedMetricPythonAutomationRuleEvaluatorModel.class,
            UserDefinedMetricPythonAutomationRuleEvaluatorModel::fromRowMapper),

    TRACE_THREAD_LLM_AS_JUDGE(Constants.TRACE_THREAD_LLM_AS_JUDGE,
            TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.class,
            TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel::fromRowMapper),

    TRACE_THREAD_USER_DEFINED_METRIC_PYTHON(Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON,
            TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.class,
            TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel::fromRowMapper),

    SPAN_LLM_AS_JUDGE(Constants.SPAN_LLM_AS_JUDGE,
            SpanLlmAsJudgeAutomationRuleEvaluatorModel.class,
            SpanLlmAsJudgeAutomationRuleEvaluatorModel::fromRowMapper),

    SPAN_USER_DEFINED_METRIC_PYTHON(Constants.SPAN_USER_DEFINED_METRIC_PYTHON,
            SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.class,
            SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel::fromRowMapper);

    @JsonValue
    private final String type;
    private final Class<? extends AutomationRuleEvaluatorModel<?>> modelClass;
    private final RowMapperFactory factory;

    /**
     * Creates an AutomationRuleEvaluatorModel instance using the type-specific factory.
     * Delegates to the appropriate model's fromRowMapper method via method reference.
     * This eliminates switch statements by using the Strategy pattern with functional interfaces.
     *
     * @param common Common fields from ResultSet
     * @param codeNode JSON representation of code field
     * @param objectMapper ObjectMapper for deserialization
     * @return Constructed model instance
     * @throws JsonProcessingException if JSON parsing fails
     */
    public AutomationRuleEvaluatorModel<?> fromRowMapper(
            AutomationRuleEvaluatorWithProjectRowMapper.CommonFields common,
            JsonNode codeNode,
            ObjectMapper objectMapper) throws JsonProcessingException {
        return factory.create(common, codeNode, objectMapper);
    }

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
