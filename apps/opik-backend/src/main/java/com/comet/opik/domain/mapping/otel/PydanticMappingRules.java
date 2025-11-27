package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for Pydantic integration.
 */
@UtilityClass
public final class PydanticMappingRules {

    public static final String SOURCE = "Pydantic";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("logfire.msg").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("logfire.msg_template").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.DROP)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("logfire.json_schema").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.DROP)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("code.").isPrefix(true).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
