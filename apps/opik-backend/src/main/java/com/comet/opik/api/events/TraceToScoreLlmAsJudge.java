package com.comet.opik.api.events;

import com.comet.opik.api.Trace;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceToScoreLlmAsJudge(
        Trace trace,
        LlmAsJudgeCode llmAsJudgeCode,
        String workspaceId,
        String userName) {
}
