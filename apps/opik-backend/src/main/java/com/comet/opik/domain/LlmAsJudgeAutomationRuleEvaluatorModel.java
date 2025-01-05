package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
public record LlmAsJudgeAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId,
        String name,
        Float samplingRate,
        @Json JsonNode code,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy) implements AutomationRuleEvaluatorModel<JsonNode> {

    @Override
    public AutomationRuleEvaluatorType type() {
        return AutomationRuleEvaluatorType.LLM_AS_JUDGE;
    }

}
