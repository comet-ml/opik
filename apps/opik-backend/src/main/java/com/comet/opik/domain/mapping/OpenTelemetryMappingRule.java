package com.comet.opik.domain.mapping;

import com.comet.opik.domain.SpanType;

/**
 * Represents a single mapping rule for OpenTelemetry attributes.
 * Each rule defines how an attribute key should be processed and categorized.
 *
 * @param isPrefix true if the rule matches by prefix, false for exact match
 * @param source   integration name (e.g., "LiveKit", "GenAI", "Logfire")
 * @param outcome  how the attribute should be categorized
 * @param spanType optional span type override
 */
public record OpenTelemetryMappingRule(String rule, boolean isPrefix, String source, Outcome outcome,
        SpanType spanType) {
    public OpenTelemetryMappingRule(String rule, boolean isPrefix, String source, Outcome outcome) {
        this(rule, isPrefix, source, outcome, null);
    }

    /**
     * Checks if this rule matches the given key.
     */
    public boolean matches(String key) {
        if (isPrefix) {
            return key.startsWith(rule);
        } else {
            return rule.equals(key);
        }
    }

    /**
     * Outcome determines how the attribute value should be processed.
     */
    public enum Outcome {
        INPUT, // Attribute goes to input JSON
        OUTPUT, // Attribute goes to output JSON
        METADATA, // Attribute goes to metadata JSON
        MODEL, // Attribute sets the model field
        PROVIDER, // Attribute sets the provider field
        USAGE, // Attribute contributes to usage map
        DROP // Attribute should be ignored
    }
}
