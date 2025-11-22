package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;

import java.util.List;

/**
 * Mapping rules for Pydantic integration.
 */
public final class PydanticMappingRules {

    private PydanticMappingRules() {
        // Utility class
    }

    public static List<OpenTelemetryMappingRule> getRules() {
        return List.of(
                new OpenTelemetryMappingRule("code.", true, "Pydantic", OpenTelemetryMappingRule.Outcome.METADATA));
    }
}
