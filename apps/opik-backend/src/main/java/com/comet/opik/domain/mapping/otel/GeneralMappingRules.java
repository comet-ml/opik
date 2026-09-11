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

    public static final String OPIK_TRACE_ID_ATTR = "opik.trace_id";
    public static final String OPIK_PARENT_SPAN_ID_ATTR = "opik.parent_span_id";
    public static final String OPIK_SPAN_ID_ATTR = "opik.span_id";
    public static final String SERVER_ADDRESS_ATTR = "server.address";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("input").isPrefix(true).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT)
                    .build(),
            // Network host of the LLM endpoint (OTel semantic convention). Kept in metadata so
            // GoogleProviderResolver can disambiguate the generic 'google' provider into the
            // Vertex AI vs Gemini API pricing variant.
            OpenTelemetryMappingRule.builder()
                    .rule(SERVER_ADDRESS_ATTR).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("output").isPrefix(true).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("thread_id").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.THREAD_ID).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("opik.tags").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.TAGS).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("opik.metadata").isPrefix(true).source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            // These attributes are consumed by OpenTelemetryMapper.toOpikSpan() to connect
            // OTEL spans to existing OPIK traces/spans. They are dropped here to prevent them
            // from leaking into input/output/metadata as regular span attributes.
            OpenTelemetryMappingRule.builder()
                    .rule(OPIK_TRACE_ID_ATTR).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.DROP)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule(OPIK_PARENT_SPAN_ID_ATTR).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.DROP)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule(OPIK_SPAN_ID_ATTR).source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.DROP)
                    .build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
