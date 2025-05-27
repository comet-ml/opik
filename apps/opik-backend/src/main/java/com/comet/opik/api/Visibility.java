package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * This enum representing the visibility status of a resource.
 * <p>
 * This enum is used to indicate whether a resource is accessible only within its own workspace
 * ({@link #PRIVATE}) or is publicly accessible to all users who have the URI to the resource
 * ({@link #PUBLIC}).
 * <p>
 */
@RequiredArgsConstructor
public enum Visibility {
    PRIVATE("private"),
    PUBLIC("public");

    @JsonValue
    private final String value;

    @JsonCreator
    public static Visibility fromString(String value) {
        return Arrays.stream(values())
                .filter(llmProvider -> llmProvider.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown visibility '%s'".formatted(value)));
    }
}
