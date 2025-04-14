package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;

public enum GuardrailType {
    TOPIC,
    PII,
    ;

    @JsonCreator
    public static GuardrailType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.toString().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown guardrail type '%s'".formatted(value)));
    }
}
