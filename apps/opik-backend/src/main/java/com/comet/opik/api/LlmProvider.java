package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum LlmProvider {
    OPEN_AI("openai", true),
    ANTHROPIC("anthropic", true),
    GEMINI("gemini", true),
    OPEN_ROUTER("openrouter", true),
    VERTEX_AI("vertex-ai", true),
    VLLM("vllm", false),
    ;

    @JsonValue
    private final String value;
    private final boolean supportsStructuredOutput;

    @JsonCreator
    public static LlmProvider fromString(String value) {
        return Arrays.stream(values())
                .filter(llmProvider -> llmProvider.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown llm provider '%s'".formatted(value)));
    }
}
