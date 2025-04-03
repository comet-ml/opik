package com.comet.opik.domain;

import java.util.Arrays;
import java.util.Optional;

public enum SpanType {
    general,
    tool,
    llm,
    guardrail,
    ;

    public static SpanType fromString(String value) {
        Optional<SpanType> type = Arrays.stream(SpanType.values())
                .filter(v -> v.name().equalsIgnoreCase(value))
                .findFirst();

        if (type.isPresent()) {
            return type.get();
        } else if ("unknown".equalsIgnoreCase(value)) {
            return null;
        }

        throw new IllegalArgumentException("Invalid SpanType: " + value);
    }
}
