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
 *     <li>input: {@code user_prompt} (interaction), {@code tool_input} (tool),
 *     {@code model} / {@code system_prompt_preview} (llm; {@code tools} is mapped to input
 *     globally), {@code hook_event} / {@code hook_name} / {@code hook_definitions} (hook)</li>
 *     <li>output: {@code response.model_output} (assistant text), {@code stop_reason},
 *     {@code response.has_tool_call} (llm response)</li>
 *     <li>usage: {@code input_tokens} / {@code output_tokens} / {@code cache_*_tokens}</li>
 *     <li>thread: {@code session.id} (groups the interaction turns into one Opik thread)</li>
 * </ul>
 * {@code new_context} is handled span-aware in {@code OpenTelemetryMapper}: it is the latest LLM
 * message on {@code claude_code.llm_request} spans (→ input) and a duplicate of the prompt / tool
 * result elsewhere (→ metadata).
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
            // --- input: the content each operation was invoked with ---
            rule("user_prompt", OpenTelemetryMappingRule.Outcome.INPUT), // claude_code.interaction
            rule("tool_input", OpenTelemetryMappingRule.Outcome.INPUT), // claude_code.tool
            // claude_code.llm_request: the request setup (tools are mapped to input globally)
            rule("model", OpenTelemetryMappingRule.Outcome.INPUT),
            rule("system_prompt_preview", OpenTelemetryMappingRule.Outcome.INPUT),
            // claude_code.hook: which hook fired and its definition
            rule("hook_event", OpenTelemetryMappingRule.Outcome.INPUT),
            rule("hook_name", OpenTelemetryMappingRule.Outcome.INPUT),
            rule("hook_definitions", OpenTelemetryMappingRule.Outcome.INPUT),
            // --- output: the model's response ---
            rule("response.model_output", OpenTelemetryMappingRule.Outcome.OUTPUT),
            rule("stop_reason", OpenTelemetryMappingRule.Outcome.OUTPUT),
            rule("response.has_tool_call", OpenTelemetryMappingRule.Outcome.OUTPUT),
            // --- usage ---
            rule("input_tokens", OpenTelemetryMappingRule.Outcome.USAGE),
            rule("output_tokens", OpenTelemetryMappingRule.Outcome.USAGE),
            rule("cache_read_tokens", OpenTelemetryMappingRule.Outcome.USAGE),
            rule("cache_creation_tokens", OpenTelemetryMappingRule.Outcome.USAGE),
            // --- thread grouping ---
            rule("session.id", OpenTelemetryMappingRule.Outcome.THREAD_ID));
    // `new_context` is handled span-aware in OpenTelemetryMapper: it is the latest LLM message on
    // llm_request spans (→ input) but a duplicate of the prompt / tool result elsewhere (dropped).

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }
}
