package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for Logfire integration.
 */
@UtilityClass
public final class LogfireMappingRules {

    public static final String SOURCE = "Logfire";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("agent_name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("all_messages").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("logfire.span_type").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.DROP)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("logfire.metrics").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.DROP)
                    .build(),
            // `model_name` rides on the PydanticAI agent-run span (not an LLM call). Keep it as
            // metadata: mapping it to MODEL would also flip the agent-run span's type to `llm` and
            // attach a model to it. The real LLM/chat span gets its model from `gen_ai.request.model`.
            OpenTelemetryMappingRule.builder()
                    .rule("model_name").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("params").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("prompt").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("response").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("result").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tools").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool_responses").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            // PydanticAI tool-execution spans carry the call arguments under `tool_arguments` and
            // the returned value under `tool_response`. Without these rules `tool_response` falls
            // through to the default INPUT bucket, so the tool span shows no output in the UI.
            OpenTelemetryMappingRule.builder()
                    .rule("tool_arguments").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool_response").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("usage").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                    .spanType(SpanType.llm).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("final_result").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
