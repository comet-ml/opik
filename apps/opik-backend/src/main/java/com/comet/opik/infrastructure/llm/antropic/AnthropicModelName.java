package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.ModelDefinition;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

/**
 * This information is taken from <a href="https://docs.anthropic.com/claude/docs/models-overview">anthropic docs</a>
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public enum AnthropicModelName implements ModelDefinition {
    CLAUDE_3_OPUS("claude-3-opus-20240229", true),
    CLAUDE_3_5_SONNET("claude-3-5-sonnet-20240620", true),
    CLAUDE_3_SONNET("claude-3-sonnet-20240229", true),
    CLAUDE_3_HAIKU("claude-3-haiku-20240307", true),
    CLAUDE_2_1("claude-2.1", false),
    CLAUDE_2_0("claude-2.0", false),
    CLAUDE_INSTANT_1_2("claude-instant-1.2", false);

    private static final String WARNING_UNKNOWN_MODEL = "could not find AnthropicModelName with value '{}'";

    private final String value;
    private final boolean structuredOutputSupported;

    @Override
    public boolean isStructuredOutputSupported() {
        return this.structuredOutputSupported;
    }

    @Override
    public LlmProvider getProvider() {
        return LlmProvider.ANTHROPIC;
    }

    public static Optional<AnthropicModelName> byValue(String value) {
        var response = Arrays.stream(AnthropicModelName.values())
                .filter(modelName -> modelName.value.equals(value))
                .findFirst();
        if (response.isEmpty()) {
            log.warn(WARNING_UNKNOWN_MODEL, value);
        }
        return response;
    }

    @Override
    public String toString() {
        return value;
    }
}
