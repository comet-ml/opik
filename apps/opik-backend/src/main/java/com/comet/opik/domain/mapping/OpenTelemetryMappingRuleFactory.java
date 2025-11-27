package com.comet.opik.domain.mapping;

import com.comet.opik.domain.mapping.otel.GenAIMappingRules;
import com.comet.opik.domain.mapping.otel.GeneralMappingRules;
import com.comet.opik.domain.mapping.otel.LangFuseMappingRules;
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

    private static final List<OpenTelemetryMappingRule> ALL_RULES = Stream.of(
            LogfireMappingRules.getRules(),
            GenAIMappingRules.getRules(),
            OpenInferenceMappingRules.getRules(),
            LiveKitMappingRules.getRules(),
            PydanticMappingRules.getRules(),
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
