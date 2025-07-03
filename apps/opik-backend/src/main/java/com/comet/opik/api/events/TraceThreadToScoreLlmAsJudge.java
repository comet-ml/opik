package com.comet.opik.api.events;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceThreadToScoreLlmAsJudge(
        @NotNull @NotEmpty List<String> threadIds,
        @NotNull UUID ruleId,
        @NotNull UUID projectId,
        @NotNull TraceThreadLlmAsJudgeCode code,
        @NotNull String workspaceId,
        @NotNull String userName) {
}
