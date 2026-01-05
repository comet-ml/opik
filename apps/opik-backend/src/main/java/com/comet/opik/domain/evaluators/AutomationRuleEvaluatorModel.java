package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.ProjectReference;
import org.jdbi.v3.json.Json;

import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

public sealed interface AutomationRuleEvaluatorModel<T> extends AutomationRuleModel permits
        LlmAsJudgeAutomationRuleEvaluatorModel, TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel,
        TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel,
        UserDefinedMetricPythonAutomationRuleEvaluatorModel,
        SpanLlmAsJudgeAutomationRuleEvaluatorModel,
        SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel {

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

    /**
     * Rebuilds this model with enriched project details.
     * Each concrete implementation provides this method using their Lombok-generated builder.
     *
     * @param projectId the legacy project ID (for backward compatibility)
     * @param projectName the legacy project name (for backward compatibility)
     * @param projects the sorted set of project references
     * @return a new instance with updated project details
     */
    AutomationRuleEvaluatorModel<?> withProjectDetails(
            UUID projectId,
            String projectName,
            SortedSet<ProjectReference> projects);
}
