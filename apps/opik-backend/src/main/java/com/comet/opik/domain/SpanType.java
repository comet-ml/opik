package com.comet.opik.domain;

import java.util.Arrays;

public enum SpanType {
    general,
    tool,
    llm,
    guardrail,
    ;

    /**
     * Not defining a span type equivalent to this value, to prevent ingestion.
     */
    public static final String UNKNOWN_VALUE = "unknown";

    public static SpanType fromString(String value) {
        return Arrays.stream(SpanType.values())
                .filter(spanType -> spanType.name().equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
    }
}
