package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for Smolagents integration.
 */
@UtilityClass
public final class SmolagentsMappingRules {

    public static final String SOURCE = "Smolagents";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("smolagents.").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
