package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import org.jdbi.v3.json.Json;

import java.util.UUID;

public sealed interface AutomationRuleEvaluatorModel<T> extends AutomationRuleModel permits
        LlmAsJudgeAutomationRuleEvaluatorModel, TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel,
        TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel,
        UserDefinedMetricPythonAutomationRuleEvaluatorModel,
        SpanLlmAsJudgeAutomationRuleEvaluatorModel,
        SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel {

    UUID projectId(); // Legacy single project field for backwards compatibility
    String projectName(); // Legacy project name field (resolved from projectId)

    @Json
    T code();

    AutomationRuleEvaluatorType type();

    @Override
    default AutomationRule.AutomationRuleAction action() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }
}
