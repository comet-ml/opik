package com.comet.opik.api.events;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceThreadToScoreUserDefinedMetricPython(
        @NotNull UUID ruleId,
        @NotNull UUID projectId,
        @NotNull @NotEmpty List<String> threadIds,
        @NotNull TraceThreadUserDefinedMetricPythonCode code,
        @NotNull String workspaceId,
        @NotNull String userName) {
}
