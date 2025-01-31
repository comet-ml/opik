package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum PromptType {

    MUSTACHE("mustache"),
    JINJA2("jinja2");

    @JsonValue
    private final String value;

    @JsonCreator
    public static PromptType fromString(String value) {
        return Arrays.stream(values())
                .filter(promptType -> promptType.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown prompt type '%s'".formatted(value)));
    }
}
