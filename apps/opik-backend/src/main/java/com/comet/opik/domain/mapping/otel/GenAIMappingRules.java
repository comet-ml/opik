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

    /** Current semantic-convention provider attribute; replaced the deprecated {@code gen_ai.system}. */
    public static final String PROVIDER_NAME_ATTR = "gen_ai.provider.name";

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
            // Replacement for the deprecated `gen_ai.system`. Instrumentations migrating to the
            // current semconv emit this one (and often both). OpenTelemetryMapper keeps
            // `gen_ai.system` authoritative when present; see PROVIDER_NAME_ATTR there.
            OpenTelemetryMappingRule.builder()
                    .rule(PROVIDER_NAME_ATTR).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.PROVIDER)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.usage.cost").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.COST).spanType(SpanType.llm).build(),
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
            // Tool call arguments/result carry the tool span's input/output. They must be
            // matched before the broad `gen_ai.tool.` prefix below, otherwise that prefix
            // would bucket them into METADATA and the tool span would lose input/output.
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.tool.call.arguments").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT)
                    .spanType(SpanType.tool).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("gen_ai.tool.call.result").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.OUTPUT)
                    .spanType(SpanType.tool).build(),
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
