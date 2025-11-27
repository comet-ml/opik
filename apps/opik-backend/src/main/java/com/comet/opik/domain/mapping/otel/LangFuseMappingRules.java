package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for LangFuse integration.
 */
@UtilityClass
public class LangFuseMappingRules {

    public static final String SOURCE = "LangFuse";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("langfuse.observation.completion_start_time").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).spanType(SpanType.llm).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
