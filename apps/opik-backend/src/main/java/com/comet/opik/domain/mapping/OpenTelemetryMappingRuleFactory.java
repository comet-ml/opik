package com.comet.opik.domain.mapping;

import com.comet.opik.domain.mapping.otel.ClaudeCodeMappingRules;
import com.comet.opik.domain.mapping.otel.GenAIMappingRules;
import com.comet.opik.domain.mapping.otel.GeneralMappingRules;
import com.comet.opik.domain.mapping.otel.LangFuseMappingRules;
import com.comet.opik.domain.mapping.otel.LiteLLMMappingRules;
import com.comet.opik.domain.mapping.otel.LiveKitMappingRules;
import com.comet.opik.domain.mapping.otel.LogfireMappingRules;
import com.comet.opik.domain.mapping.otel.OpenInferenceMappingRules;
import com.comet.opik.domain.mapping.otel.PydanticMappingRules;
import com.comet.opik.domain.mapping.otel.SmolagentsMappingRules;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Factory that provides access to all OpenTelemetry mapping rules organized by integration.
 * This factory aggregates rules from all integrations and provides a unified interface.
 */
@UtilityClass
public class OpenTelemetryMappingRuleFactory {

    /**
     * Instrumentation scope name Claude Code / Claude Agent SDK spans are tagged with. Used only
     * for the cosmetic {@code metadata.integration} label; which rules apply is decided per span
     * by {@link #isClaudeCodeSpan(String)} instead (see its javadoc for why).
     */
    public static final String CLAUDE_CODE_INSTRUMENTATION = "com.anthropic.claude_code.tracing";

    /**
     * Every Claude Code OTEL span name is prefixed with this (e.g. {@code claude_code.llm_request},
     * {@code claude_code.tool}). See {@link #isClaudeCodeSpan(String)}.
     */
    private static final String CLAUDE_CODE_SPAN_PREFIX = "claude_code.";

    private static final List<OpenTelemetryMappingRule> ALL_RULES = Stream.of(
            LogfireMappingRules.getRules(),
            GenAIMappingRules.getRules(),
            OpenInferenceMappingRules.getRules(),
            LiveKitMappingRules.getRules(),
            PydanticMappingRules.getRules(),
            LiteLLMMappingRules.getRules(),
            GeneralMappingRules.getRules(),
            SmolagentsMappingRules.getRules(),
            LangFuseMappingRules.getRules())
            .flatMap(List::stream)
            .toList();

    private static final Set<String> INTEGRATIONS_TO_IGNORE = Set.of(
            LogfireMappingRules.SOURCE.toLowerCase());

    /**
     * Finds the first matching rule for the given key.
     * Rules are checked in the order they were registered.
     *
     * @param key the attribute key to find a rule for
     * @return an Optional containing the matching rule, or empty if no rule matches
     */
    public static Optional<OpenTelemetryMappingRule> findRule(String key) {
        return ALL_RULES.stream()
                .filter(rule -> rule.matches(key))
                .findFirst();
    }

    /**
     * Finds the matching rule for the given key, applying Claude Code's rules first when the span
     * is a Claude Code span. Falls back to the global rules.
     *
     * @param key the attribute key to find a rule for
     * @param isClaudeCode whether the current span is a Claude Code span (see {@link #isClaudeCodeSpan(String)})
     * @return an Optional containing the matching rule, or empty if no rule matches
     */
    public static Optional<OpenTelemetryMappingRule> findRule(String key, boolean isClaudeCode) {
        if (!isClaudeCode) {
            return findRule(key);
        }
        return ClaudeCodeMappingRules.findRule(key).or(() -> findRule(key));
    }

    /**
     * Whether the given OTEL span name belongs to Claude Code / Claude Agent SDK.
     * <p>
     * This is decided per span, by name, rather than from the batch-level detected
     * {@code integrationName} (see {@code OpenTelemetryService}): an {@code ExportTraceServiceRequest}
     * can carry spans from more than one {@code ScopeSpans}/integration in the same batch, but
     * {@code integrationName} is resolved once for the whole batch. Gating Claude-specific routing
     * (default attribute bucket, provider, tool-output extraction — see
     * {@code OpenTelemetryMapper#enrichSpanWithAttributes}) on that batch-wide value would either
     * misroute a differently-scoped span as Claude Code, or skip Claude routing for a genuine
     * Claude Code span, depending on which scope happened to be seen first. The span name has no
     * such ambiguity: Claude Code always names its own spans with this prefix.
     */
    public static boolean isClaudeCodeSpan(String spanName) {
        return spanName != null && spanName.startsWith(CLAUDE_CODE_SPAN_PREFIX);
    }

    /**
     * Gets all rules for a specific integration.
     *
     * @param integrationName the name of the integration
     * @return a list of rules for that integration
     */
    public static List<OpenTelemetryMappingRule> getRulesForIntegration(String integrationName) {
        return ALL_RULES.stream()
                .filter(rule -> rule.getSource().equals(integrationName))
                .toList();
    }

    /**
     * List of 'non-real' integrations that should be ignored when detecting integration names.
     * For example, Pydantic uses logfire under the hood, so part of the calls are tagged as 'logfire',
     * but we want to see Pydantic.
     *
     * @param name the instrumentation name to check
     * @return true if the instrumentation is valid (not in the ignore list), false otherwise
     */
    public static boolean isValidInstrumentation(String name) {
        if (Objects.isNull(name)) {
            return false;
        }
        return !INTEGRATIONS_TO_IGNORE.contains(name.toLowerCase());
    }
}
