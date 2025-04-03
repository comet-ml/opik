package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ProjectStatus {
    PRIVATE("private"),
    PUBLIC("public");

    @JsonValue
    private final String value;

    ProjectStatus(String value) {
        this.value = value;
    }

    @JsonCreator
    public static ProjectStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(llmProvider -> llmProvider.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown project status '%s'".formatted(value)));
    }
}
