package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@RequiredArgsConstructor
public enum ProjectVisibility {
    PRIVATE("private"),
    PUBLIC("public");

    @JsonValue
    private final String value;

    @JsonCreator
    public static ProjectVisibility fromString(String value) {
        return Arrays.stream(values())
                .filter(llmProvider -> llmProvider.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown project visibility '%s'".formatted(value)));
    }
}
