package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode;

@Builder(toBuilder = true)
public record TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId, // Legacy single project field for backwards compatibility
        String projectName, // Legacy project name field (resolved from projectId)
        Set<UUID> projectIds, // New multi-project field
        String name,
        Float samplingRate,
        boolean enabled,
        String filters,
        @Json TraceThreadLlmAsJudgeCode code,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            AutomationRuleEvaluatorModel<TraceThreadLlmAsJudgeCode> {

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE;
    }

    @Override
    public AutomationRuleEvaluatorModel<?> withProjectIds(Set<UUID> projectIds) {
        return toBuilder().projectIds(projectIds).build();
    }

    record TraceThreadLlmAsJudgeCode(
            LlmAsJudgeCodeParameters model,
            List<LlmAsJudgeCodeMessage> messages,
            List<LlmAsJudgeCodeSchema> schema) {
    }

}
