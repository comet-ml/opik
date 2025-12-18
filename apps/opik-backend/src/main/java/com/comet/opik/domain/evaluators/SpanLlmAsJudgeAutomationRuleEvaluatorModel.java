package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.ProjectReference;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode;

@Builder(toBuilder = true)
public record SpanLlmAsJudgeAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId,
        String projectName,
        Set<UUID> projectIds,
        SortedSet<ProjectReference> projects,
        String name,
        Float samplingRate,
        boolean enabled,
        String filters,
        @Json SpanLlmAsJudgeCode code,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            AutomationRuleEvaluatorModel<SpanLlmAsJudgeCode> {

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE;
    }

    @Override
    public AutomationRuleEvaluatorModel<?> withProjectIds(Set<UUID> projectIds) {
        return toBuilder().projectIds(projectIds).build();
    }

    @Override
    public AutomationRuleEvaluatorModel<?> withProjectDetails(
            UUID projectId, String projectName, SortedSet<ProjectReference> projects) {
        return toBuilder().projectId(projectId).projectName(projectName).projects(projects).build();
    }

    public record SpanLlmAsJudgeCode(LlmAsJudgeCodeParameters model,
            List<LlmAsJudgeCodeMessage> messages,
            Map<String, String> variables,
            List<LlmAsJudgeCodeSchema> schema) {
    }
}
