package com.comet.opik.api.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceToScoreLlmAsJudge(
        @NotNull Trace trace,
        @NotNull UUID ruleId,
        @NotNull String ruleName,
        @NotNull LlmAsJudgeCode llmAsJudgeCode,
        @NotNull String workspaceId,
        @NotNull String userName,
        String categoryName,
        @NotNull Map<String, String> scoreNameMapping,
        @NotNull PromptType promptType,
        @Nullable UUID experimentId) {
}
