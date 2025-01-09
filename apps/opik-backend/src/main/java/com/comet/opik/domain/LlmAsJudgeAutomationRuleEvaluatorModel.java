package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.LlmAsJudgeOutputSchemaType;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
public record LlmAsJudgeAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId,
        String name,
        Float samplingRate,
        @Json LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode code,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            AutomationRuleEvaluatorModel<LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode> {

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.LLM_AS_JUDGE;
    }

    record LlmAsJudgeCode(LlmAsJudgeCodeParameters model,
            List<LlmAsJudgeCodeMessage> messages,
            Map<String, String> variables,
            List<LlmAsJudgeCodeSchema> schema) {
    }
    record LlmAsJudgeCodeParameters(String name, Double temperature) {
    }
    record LlmAsJudgeCodeMessage(String role, String content) {
    }
    record LlmAsJudgeCodeSchema(String name, LlmAsJudgeOutputSchemaType type, String description) {
    }
}
