package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum PromptVersionAction {
    UPDATE_BLUEPRINT("update_blueprint"),
    NO_ACTION("no_action");

    @JsonValue
    private final String value;

    @JsonCreator
    public static PromptVersionAction fromString(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown PromptVersionAction '%s'".formatted(value)));
    }
}
