package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;

import java.util.List;

/**
 * Mapping rules for general/common attributes.
 */
public final class GeneralMappingRules {

    private GeneralMappingRules() {
        // Utility class
    }

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
                new OpenTelemetryMappingRule("input", true, "General", OpenTelemetryMappingRule.Outcome.INPUT),
                new OpenTelemetryMappingRule("output", true, "General", OpenTelemetryMappingRule.Outcome.OUTPUT),
                new OpenTelemetryMappingRule("thread_id", false, "General", OpenTelemetryMappingRule.Outcome.METADATA));
    }
}
