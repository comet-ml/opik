package com.comet.opik.api.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceToScoreUserDefinedMetricPython(
        @NotNull Trace trace,
        @NotNull UUID ruleId,
        @NotNull String ruleName,
        @NotNull AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode code,
        @NotNull String workspaceId,
        @NotNull String userName) {
}
