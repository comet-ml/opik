package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * Entity type for manual evaluation requests.
 * Represents the type of entity (trace or thread) that will be evaluated.
 */
@Getter
@RequiredArgsConstructor
public enum ManualEvaluationEntityType {
    TRACE("trace"),
    THREAD("thread"),
    SPAN("span");

    @JsonValue
    private final String value;

    @JsonCreator
    public static ManualEvaluationEntityType fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown entity type '%s'. Valid values are: trace, thread, span".formatted(value)));
    }
}
