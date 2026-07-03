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
     * Instrumentation scope name Claude Code / Claude Agent SDK spans are tagged with.
     * Claude Code rules are applied only for this integration (see {@link #findRule(String, String)}).
     */
    public static final String CLAUDE_CODE_INSTRUMENTATION = "com.anthropic.claude_code.tracing";

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
     * Finds the matching rule for the given key, applying integration-specific rules first.
     * Claude Code rules are scoped so their reclassification (e.g. session.id → thread, tokens →
     * usage) doesn't affect other integrations. Falls back to the global rules.
     *
     * @param key the attribute key to find a rule for
     * @param integrationName the detected instrumentation scope name (may be null)
     * @return an Optional containing the matching rule, or empty if no rule matches
     */
    public static Optional<OpenTelemetryMappingRule> findRule(String key, String integrationName) {
        if (isClaudeCode(integrationName)) {
            var claudeCodeRule = ClaudeCodeMappingRules.getRules().stream()
                    .filter(rule -> rule.matches(key))
                    .findFirst();
            if (claudeCodeRule.isPresent()) {
                return claudeCodeRule;
            }
        }
        return findRule(key);
    }

    /**
     * Whether the detected integration is Claude Code / Claude Agent SDK.
     */
    public static boolean isClaudeCode(String integrationName) {
        return CLAUDE_CODE_INSTRUMENTATION.equalsIgnoreCase(integrationName);
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
