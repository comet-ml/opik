package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import org.jdbi.v3.json.Json;

import java.util.Set;
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

    /**
     * Rebuilds this model with new project IDs.
     * Each concrete implementation provides this method using their Lombok-generated builder.
     *
     * @param projectIds the new set of project IDs
     * @return a new instance with updated project IDs
     */
    AutomationRuleEvaluatorModel<?> withProjectIds(Set<UUID> projectIds);
}
