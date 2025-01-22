package com.comet.opik.api.events;

import com.comet.opik.api.Trace;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;

import java.util.UUID;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceToScoreUserDefinedMetricPython(
        Trace trace,
        UUID ruleId,
        String ruleName,
        LlmAsJudgeCode llmAsJudgeCode,
        String workspaceId,
        String userName) implements ScoringMessage {
}
