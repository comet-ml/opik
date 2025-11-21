package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;

import java.util.List;

/**
 * Mapping rules for OpenInference integration.
 */
public final class OpenInferenceMappingRules {

    private OpenInferenceMappingRules() {
        // Utility class
    }

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
                new OpenTelemetryMappingRule("llm.invocation_parameters.*", true, "OpenInference",
                        OpenTelemetryMappingRule.Outcome.INPUT,
                        SpanType.llm),
                new OpenTelemetryMappingRule("llm.model_name", false, "OpenInference",
                        OpenTelemetryMappingRule.Outcome.MODEL, SpanType.llm),
                new OpenTelemetryMappingRule("llm.token_count.", true, "OpenInference",
                        OpenTelemetryMappingRule.Outcome.USAGE, SpanType.llm));
    }
}
