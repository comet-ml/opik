package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRule;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mapping rules for Claude Code / Claude Agent SDK spans.
 * <p>
 * These rules are applied only to spans whose OTEL span name is prefixed {@code claude_code.}
 * (see {@code OpenTelemetryMappingRuleFactory#isClaudeCodeSpan} and {@code OpenTelemetryMapper});
 * decided per span rather than from the batch-detected integration name, since a single OTLP batch
 * can mix scopes from more than one integration. For Claude Code spans the default bucket for
 * unmapped attributes is metadata (not input), so this list only needs to <em>promote</em> the handful of attributes
 * that carry real content into input/output/usage/thread; everything else (session/user identity,
 * durations, prompt hashes, tool ids, hook counters, ...) falls through to metadata automatically.
 * <ul>
 *     <li>input: {@code user_prompt} (interaction), {@code tool_input} (tool),
 *     {@code system_prompt_preview} (llm; {@code tools} is mapped to input globally),
 *     {@code hook_event} / {@code hook_name} / {@code hook_definitions} (hook)</li>
 *     <li>model: {@code model} (llm; sets the span's model field, not input)</li>
 *     <li>output: {@code response.model_output} (assistant text), {@code stop_reason},
 *     {@code response.has_tool_call} (llm response)</li>
 *     <li>usage: {@code input_tokens} / {@code output_tokens} / {@code cache_*_tokens}</li>
 *     <li>thread: {@code session.id} (groups the interaction turns into one Opik thread)</li>
 * </ul>
 * The provider is always {@code anthropic} for this integration; since no attribute carries it,
 * {@code OpenTelemetryMapper} sets it directly for Claude Code spans rather than via a rule here.
 * <p>
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
        return rule(key, outcome, null);
    }

    private static OpenTelemetryMappingRule rule(String key, OpenTelemetryMappingRule.Outcome outcome,
            SpanType spanType) {
        return OpenTelemetryMappingRule.builder().rule(key).source(SOURCE).outcome(outcome).spanType(spanType)
                .build();
    }

    private static final List<OpenTelemetryMappingRule> RULES = List.of(
            // --- input: the content each operation was invoked with ---
            rule("user_prompt", OpenTelemetryMappingRule.Outcome.INPUT), // claude_code.interaction
            rule("tool_input", OpenTelemetryMappingRule.Outcome.INPUT, SpanType.tool), // claude_code.tool
            // claude_code.llm_request: the request setup (tools are mapped to input globally)
            rule("model", OpenTelemetryMappingRule.Outcome.MODEL, SpanType.llm),
            rule("system_prompt_preview", OpenTelemetryMappingRule.Outcome.INPUT, SpanType.llm),
            // claude_code.hook: which hook fired and its definition
            rule("hook_event", OpenTelemetryMappingRule.Outcome.INPUT),
            rule("hook_name", OpenTelemetryMappingRule.Outcome.INPUT),
            rule("hook_definitions", OpenTelemetryMappingRule.Outcome.INPUT),
            // --- output: the model's response ---
            rule("response.model_output", OpenTelemetryMappingRule.Outcome.OUTPUT, SpanType.llm),
            rule("stop_reason", OpenTelemetryMappingRule.Outcome.OUTPUT, SpanType.llm),
            rule("response.has_tool_call", OpenTelemetryMappingRule.Outcome.OUTPUT, SpanType.llm),
            // --- usage ---
            rule("input_tokens", OpenTelemetryMappingRule.Outcome.USAGE, SpanType.llm),
            rule("output_tokens", OpenTelemetryMappingRule.Outcome.USAGE, SpanType.llm),
            rule("cache_read_tokens", OpenTelemetryMappingRule.Outcome.USAGE, SpanType.llm),
            rule("cache_creation_tokens", OpenTelemetryMappingRule.Outcome.USAGE, SpanType.llm),
            // --- thread grouping ---
            rule("session.id", OpenTelemetryMappingRule.Outcome.THREAD_ID));
    // `new_context` is handled span-aware in OpenTelemetryMapper: it is the latest LLM message on
    // llm_request spans (→ input) but a duplicate of the prompt / tool result elsewhere (dropped).

    // Every rule above is an exact-match key (no prefix rules), so a Map gives O(1) lookup instead
    // of scanning the list per attribute.
    private static final Map<String, OpenTelemetryMappingRule> RULES_BY_KEY = RULES.stream()
            .collect(Collectors.toMap(OpenTelemetryMappingRule::getRule, Function.identity()));

    public static List<OpenTelemetryMappingRule> getRules() {
        return RULES;
    }

    public static Optional<OpenTelemetryMappingRule> findRule(String key) {
        return Optional.ofNullable(RULES_BY_KEY.get(key));
    }
}
