package com.comet.opik.api.events;

import com.comet.opik.api.Span;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record SpanToScoreUserDefinedMetricPython(
        @NotNull Span span,
        @NotNull UUID ruleId,
        @NotNull String ruleName,
        @NotNull SpanUserDefinedMetricPythonCode code,
        @NotNull String workspaceId,
        @NotNull String userName) {
}
