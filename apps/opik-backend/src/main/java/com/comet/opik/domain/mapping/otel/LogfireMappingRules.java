package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;

import java.util.List;

/**
 * Mapping rules for Logfire integration.
 */
public final class LogfireMappingRules {

    private LogfireMappingRules() {
        // Utility class
    }

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
                new OpenTelemetryMappingRule("agent_name", false, "Logfire", OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("all_messages", false, "Logfire", OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("logfire.msg", false, "Pydantic",
                        OpenTelemetryMappingRule.Outcome.METADATA),
                new OpenTelemetryMappingRule("logfire.msg_template", false, "Pydantic",
                        OpenTelemetryMappingRule.Outcome.DROP),
                new OpenTelemetryMappingRule("logfire.json_schema", false, "Pydantic",
                        OpenTelemetryMappingRule.Outcome.DROP),
                new OpenTelemetryMappingRule("logfire.span_type", false, "Logfire",
                        OpenTelemetryMappingRule.Outcome.DROP), // always general
                new OpenTelemetryMappingRule("model_name", false, "Logfire", OpenTelemetryMappingRule.Outcome.MODEL,
                        SpanType.llm),
                new OpenTelemetryMappingRule("params", false, "Logfire", OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("prompt", false, "Logfire", OpenTelemetryMappingRule.Outcome.INPUT,
                        SpanType.llm),
                new OpenTelemetryMappingRule("response", false, "Logfire", OpenTelemetryMappingRule.Outcome.OUTPUT),
                new OpenTelemetryMappingRule("result", false, "Logfire", OpenTelemetryMappingRule.Outcome.OUTPUT),
                new OpenTelemetryMappingRule("tools", false, "Logfire", OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("tool_responses", false, "Logfire",
                        OpenTelemetryMappingRule.Outcome.OUTPUT),
                new OpenTelemetryMappingRule("usage", false, "Logfire", OpenTelemetryMappingRule.Outcome.USAGE,
                        SpanType.llm));
    }
}
