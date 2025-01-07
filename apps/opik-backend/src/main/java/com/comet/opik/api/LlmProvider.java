package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum LlmProvider {
    OPEN_AI("openai"),
    ANTHROPIC("anthropic");

    @JsonValue
    private final String value;

    @JsonCreator
    public static LlmProvider fromString(@NonNull String value) {
        return Arrays.stream(values())
                .filter(llmProvider -> llmProvider.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown llm provider '%s'".formatted(value)));
    }
}
