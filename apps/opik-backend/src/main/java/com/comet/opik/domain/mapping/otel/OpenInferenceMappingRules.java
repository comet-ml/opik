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
                    .rule("llm.invocation_parameters.*").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.INPUT).spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.model_name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.MODEL)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.token_count.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.USAGE).spanType(SpanType.llm).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
