package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for GenAI integration.
 */
@UtilityClass
public final class GenAIMappingRules {

    public static final String SOURCE = "GenAI";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.prompt").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.completion").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.request_model").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.response_model").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.request.model").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.request.temperature").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.response.id").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.response.model").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.response.finish_reasons").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.system").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.PROVIDER)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.usage.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.USAGE).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.input.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.output.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.system_instructions").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.cost.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.tool.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.agent.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.token.type").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.framework").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.request.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.response").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.operation.name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.conversation.id").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.THREAD_ID).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
