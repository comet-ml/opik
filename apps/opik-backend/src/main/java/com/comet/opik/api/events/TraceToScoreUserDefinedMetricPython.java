package com.comet.opik.api.events;

import com.comet.opik.api.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record TraceToScoreUserDefinedMetricPython(
        Trace trace,
        UUID ruleId,
        String ruleName,
        AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode code,
        String workspaceId,
        String userName) {
}
