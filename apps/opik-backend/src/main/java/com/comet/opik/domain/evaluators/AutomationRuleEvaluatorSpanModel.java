package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import org.jdbi.v3.json.Json;

public sealed interface AutomationRuleEvaluatorSpanModel<T> extends AutomationRuleModel permits
        SpanLlmAsJudgeAutomationRuleEvaluatorModel {

    @Json
    T code();

    AutomationRuleEvaluatorType type();

    @Override
    default AutomationRule.AutomationRuleAction action() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }
}
