package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;

import java.util.List;

/**
 * Mapping rules for GenAI integration.
 */
public final class GenAIMappingRules {

    private GenAIMappingRules() {
        // Utility class
    }

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
                new OpenTelemetryMappingRule("gen_ai.prompt", false, "GenAI", OpenTelemetryMappingRule.Outcome.INPUT,
                        SpanType.llm),
                new OpenTelemetryMappingRule("gen_ai.completion", false, "GenAI",
                        OpenTelemetryMappingRule.Outcome.OUTPUT),
                new OpenTelemetryMappingRule("gen_ai.request_model", false, "GenAI",
                        OpenTelemetryMappingRule.Outcome.MODEL, SpanType.llm),
                new OpenTelemetryMappingRule("gen_ai.response_model", false, "GenAI",
                        OpenTelemetryMappingRule.Outcome.MODEL, SpanType.llm),
                new OpenTelemetryMappingRule("gen_ai.request.model", false, "GenAI",
                        OpenTelemetryMappingRule.Outcome.MODEL, SpanType.llm),
                new OpenTelemetryMappingRule("gen_ai.response.model", false, "GenAI",
                        OpenTelemetryMappingRule.Outcome.MODEL, SpanType.llm),
                new OpenTelemetryMappingRule("gen_ai.system", false, "GenAI", OpenTelemetryMappingRule.Outcome.PROVIDER,
                        SpanType.llm),
                new OpenTelemetryMappingRule("gen_ai.usage.", true, "GenAI", OpenTelemetryMappingRule.Outcome.USAGE,
                        SpanType.llm),
                new OpenTelemetryMappingRule("gen_ai.request.", true, "GenAI", OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("gen_ai.response", true, "GenAI",
                        OpenTelemetryMappingRule.Outcome.OUTPUT));
    }
}
