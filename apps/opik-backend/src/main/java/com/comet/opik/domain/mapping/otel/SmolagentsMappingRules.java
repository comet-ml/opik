package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;

import java.util.List;

/**
 * Mapping rules for Smolagents integration.
 */
public final class SmolagentsMappingRules {

    private SmolagentsMappingRules() {
        // Utility class
    }

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
                new OpenTelemetryMappingRule("smolagents.", true, "Smolagents",
                        OpenTelemetryMappingRule.Outcome.METADATA));
    }
}
