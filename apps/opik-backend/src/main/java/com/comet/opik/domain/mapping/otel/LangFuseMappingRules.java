package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;

import java.util.List;

/**
 * Mapping rules for LangFuse integration.
 */
public class LangFuseMappingRules {

    private LangFuseMappingRules() {
        // Utility class
    }

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
            new OpenTelemetryMappingRule("langfuse.observation.completion_start_time", false, "LangFuse", OpenTelemetryMappingRule.Outcome.METADATA,
                    SpanType.llm)
            );
    }
}
