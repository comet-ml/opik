package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for Claude Code / Claude Agent SDK spans.
 * <p>
 * Claude Code emits its content on span attributes that don't match any generic rule, so without
 * these they all fall into the input attribute-bag (see {@code OpenTelemetryMapper}). These rules
 * surface the content as clean span input/output:
 * <ul>
 *     <li>{@code user_prompt} on {@code claude_code.interaction} → trace input</li>
 *     <li>{@code tool_input} on {@code claude_code.tool} → tool span input</li>
 *     <li>{@code response.model_output} on {@code claude_code.llm_request} → LLM span output
 *     (assistant text, emitted under the detailed-tracing beta)</li>
 * </ul>
 * The tool result is emitted as a {@code tool.output} span event and is mapped to the tool span
 * output directly in {@code OpenTelemetryMapper}, not here.
 */
@UtilityClass
public final class ClaudeCodeMappingRules {

    public static final String SOURCE = "ClaudeCode";

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            OpenTelemetryMappingRule.builder()
                    .rule("user_prompt").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("user_prompt_length").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool_input").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.INPUT).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool_input_truncated").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.METADATA)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("tool_input_original_length").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("response.model_output").source(SOURCE).outcome(OpenTelemetryMappingRule.Outcome.OUTPUT)
                    .build(),
            OpenTelemetryMappingRule.builder()
                    .rule("response.model_output_truncated").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build(),
            OpenTelemetryMappingRule.builder()
                    .rule("response.model_output_original_length").source(SOURCE)
                    .outcome(OpenTelemetryMappingRule.Outcome.METADATA).build());

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
