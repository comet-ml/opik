package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for OpenInference integration.
 */
@UtilityClass
public final class OpenInferenceMappingRules {

    public static final String SOURCE = "OpenInference";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("llm.invocation_parameters.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.prompts.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.input_messages.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.output_messages.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.model_name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.provider").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.PROVIDER)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.token_count.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.USAGE).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("input.value").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("output.value").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool.name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool.description").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool.parameters").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT)
                    .spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool.output").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT)
                    .spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("session.id").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.THREAD_ID).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
