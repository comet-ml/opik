package com.comet.opik.domain.mapping;

import com.comet.opik.domain.SpanType;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

/**
 * Represents a single mapping rule for OpenTelemetry attributes.
 * Each rule defines how an attribute key should be processed and categorized.
 */
@Data
@Builder
public class OpenTelemetryMappingRule {
    /** OpenTelemetry attribute key or prefix to match */
    @NonNull String rule;
    /** true if the rule matches by prefix, false for exact match */
    boolean isPrefix;
    /** integration name (e.g., "LiveKit", "GenAI", "Logfire") */
    @NonNull String source;
    /** how the attribute should be categorized */
    @NonNull Outcome outcome;
    /** optional span type override */
    SpanType spanType;

    /**
     * Checks if this rule matches the given key.
     */
    public boolean matches(String key) {
        if (isPrefix) {
            return key != null && key.startsWith(rule);
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
        TAGS, // Attribute contributes to tag set
        THREAD_ID, // Attribute sets the threadId for trace grouping (e.g., gen_ai.conversation.id)
        DROP // Attribute should be ignored
    }
}
