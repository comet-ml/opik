package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for general/common attributes.
 */
@UtilityClass
public final class GeneralMappingRules {

    public static final String SOURCE = "General";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("input").isPrefix(true).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("output").isPrefix(true).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("error.type").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("error.message").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("error.stack").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("error.stacktrace").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("exception.type").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("exception.message").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("exception.stacktrace").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("server.address").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("server.port").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("thread_id").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.THREAD_ID).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("opik.tags").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.TAGS).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("opik.metadata").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
