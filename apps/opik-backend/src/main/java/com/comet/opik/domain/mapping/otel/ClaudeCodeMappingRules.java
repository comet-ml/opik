package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Mapping rules for Claude Code / Claude Agent SDK spans.
 * <p>
 * These rules are applied only when the instrumentation scope is
 * {@code com.anthropic.claude_code.tracing} (see {@code OpenTelemetryMappingRuleFactory} and
 * {@code OpenTelemetryMapper}). For Claude Code spans the default bucket for unmapped attributes
 * is metadata (not input), so this list only needs to <em>promote</em> the handful of attributes
 * that carry real content into input/output/usage/thread; everything else (session/user identity,
 * durations, prompt hashes, tool ids, hook counters, ...) falls through to metadata automatically.
 * <ul>
 *     <li>{@code user_prompt} → input (root {@code claude_code.interaction} → trace input)</li>
 *     <li>{@code tool_input} → input ({@code claude_code.tool})</li>
 *     <li>{@code response.model_output} → output ({@code claude_code.llm_request}; assistant text)</li>
 *     <li>{@code input_tokens} / {@code output_tokens} / {@code cache_*_tokens} → usage</li>
 *     <li>{@code session.id} → thread id (groups the interaction turns into one Opik thread)</li>
 *     <li>{@code new_context} → dropped (verbatim duplicate of the prompt / tool result)</li>
 * </ul>
 * The tool result is emitted as a {@code tool.output} span event and mapped to the tool span
 * output directly in {@code OpenTelemetryMapper}.
 */
@UtilityClass
public final class ClaudeCodeMappingRules {

    public static final String SOURCE = "ClaudeCode";

    private static OpenTelemetryMappingRule rule(String key, OpenTelemetryMappingRule.Outcome outcome) {
        return OpenTelemetryMappingRule.builder().rule(key).source(SOURCE).outcome(outcome).build();
    }

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            rule("user_prompt", OpenTelemetryMappingRule.Outcome.INPUT),
            rule("tool_input", OpenTelemetryMappingRule.Outcome.INPUT),
            rule("response.model_output", OpenTelemetryMappingRule.Outcome.OUTPUT),
            rule("input_tokens", OpenTelemetryMappingRule.Outcome.USAGE),
            rule("output_tokens", OpenTelemetryMappingRule.Outcome.USAGE),
            rule("cache_read_tokens", OpenTelemetryMappingRule.Outcome.USAGE),
            rule("cache_creation_tokens", OpenTelemetryMappingRule.Outcome.USAGE),
            rule("session.id", OpenTelemetryMappingRule.Outcome.THREAD_ID),
            // Verbatim duplicate of user_prompt (interaction) / the tool.output event (tool, llm).
            rule("new_context", OpenTelemetryMappingRule.Outcome.DROP));

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
