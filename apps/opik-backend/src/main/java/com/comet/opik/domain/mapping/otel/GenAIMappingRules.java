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

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.prompt").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.completion").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.request_model").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL).spanType(SpanType.llm).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.response_model").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL).spanType(SpanType.llm).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.request.model").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL).spanType(SpanType.llm).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.response.model").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL).spanType(SpanType.llm).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.system").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.PROVIDER).spanType(SpanType.llm).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.usage.").isPrefix(true).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.USAGE).spanType(SpanType.llm).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.request.").isPrefix(true).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
                OpenTelemetryMappingRule.builder()
                        .rule("gen_ai.response").isPrefix(true).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build());
    }
}
