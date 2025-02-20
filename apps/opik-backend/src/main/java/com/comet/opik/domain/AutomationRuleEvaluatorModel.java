package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import org.jdbi.v3.json.Json;

public sealed interface AutomationRuleEvaluatorModel<T> extends AutomationRuleModel
        permits LlmAsJudgeAutomationRuleEvaluatorModel, UserDefinedMetricPythonAutomationRuleEvaluatorModel {

    @Json
    T code();

    AutomationRuleEvaluatorType type();

    @Override
    default AutomationRule.AutomationRuleAction action() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }
}
