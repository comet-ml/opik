package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum LlmProvider {
    OPEN_AI("openai"),
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    OPEN_ROUTER("openrouter"),
    VERTEX_AI("vertex-ai"),
    BEDROCK("bedrock"),
    CUSTOM_LLM("custom-llm"),
    OPIK_FREE("opik-free"),
    ;

    @JsonValue
    private final String value;

    @JsonCreator
    public static LlmProvider fromString(String value) {
        return Arrays.stream(values())
                .filter(llmProvider -> llmProvider.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown llm provider '%s'".formatted(value)));
    }

    /**
     * Checks if this provider supports custom naming (multiple instances with different names).
     * Providers that support naming can have multiple configurations distinguished by provider_name.
     *
     * @return true if this provider supports custom naming (CUSTOM_LLM, BEDROCK), false otherwise
     */
    public boolean supportsProviderName() {
        return this == CUSTOM_LLM || this == BEDROCK;
    }
}
