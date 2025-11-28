package com.comet.opik.api.events;

import com.comet.opik.api.Span;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record SpanToScoreLlmAsJudge(
        @NotNull Span span,
        @NotNull UUID ruleId,
        @NotNull String ruleName,
        @NotNull SpanLlmAsJudgeCode llmAsJudgeCode,
        @NotNull String workspaceId,
        @NotNull String userName) {
}
