package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode;

@Builder(toBuilder = true)
public record TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel(
        UUID id,
        UUID projectId,
        String projectName,
        String name,
        Float samplingRate,
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

    record TraceThreadLlmAsJudgeCode(
            LlmAsJudgeCodeParameters model,
            List<LlmAsJudgeCodeMessage> messages,
            List<LlmAsJudgeCodeSchema> schema) {
    }

}
