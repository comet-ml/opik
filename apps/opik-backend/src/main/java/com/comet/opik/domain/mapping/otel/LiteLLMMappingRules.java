package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for LiteLLM integration.
 * LiteLLM uses non-standard {@code llm.*} namespace attributes.
 */
@UtilityClass
public final class LiteLLMMappingRules {

    public static final String SOURCE = "LiteLLM";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("llm.is_streaming").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.request.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.response.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("llm.user").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("metadata.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("hidden_params").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
