package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum PromptVersionType {

    PROMPT_VERSION("prompt_version"),
    MASK("mask");

    @JsonValue
    private final String value;

    @JsonCreator
    public static PromptVersionType fromString(String value) {
        return Arrays.stream(values())
                .filter(versionType -> versionType.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown prompt version type '%s'".formatted(value)));
    }
}
