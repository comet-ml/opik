package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for Logfire integration.
 */
@UtilityClass
public final class LogfireMappingRules {

    public static final String SOURCE = "Logfire";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("agent_name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("all_messages").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("logfire.span_type").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.DROP)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("model_name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("params").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("prompt").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("response").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("result").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tools").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool_responses").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("usage").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                    .spanType(SpanType.llm).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
