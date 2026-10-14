package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This information is taken from <a href="https://docs.anthropic.com/en/docs/about-claude/models">Anthropic docs</a>
 */
@Getter
@RequiredArgsConstructor
public enum AnthropicModelName implements StructuredOutputSupported {
    CLAUDE_SONNET_3_7("claude-3-7-sonnet-20250219"),
    CLAUDE_FABLE_5("claude-fable-5"),
    CLAUDE_FABLE_5_1("claude-fable-5-1"),
    CLAUDE_HAIKU_4_5("claude-haiku-4-5-20251001"),
    CLAUDE_OPUS_4_1("claude-opus-4-1-20250805"),
    CLAUDE_OPUS_4("claude-opus-4-20250514"),
    CLAUDE_OPUS_4_5("claude-opus-4-5-20251101"),
    CLAUDE_OPUS_4_6("claude-opus-4-6"),
    CLAUDE_OPUS_4_7("claude-opus-4-7"),
    CLAUDE_OPUS_4_8("claude-opus-4-8"),
    CLAUDE_OPUS_5("claude-opus-5"),
    CLAUDE_SONNET_4("claude-sonnet-4-20250514"),
    CLAUDE_SONNET_4_5("claude-sonnet-4-5"),
    CLAUDE_SONNET_4_5_20250929("claude-sonnet-4-5-20250929"),
    CLAUDE_SONNET_4_6("claude-sonnet-4-6"),
    CLAUDE_SONNET_5("claude-sonnet-5"),
    CLAUDE_MYTHOS_5("claude-mythos-5"),
    CLAUDE_MYTHOS_5_1("claude-mythos-5-1"),
    CLAUDE_MYTHOS_PREVIEW("claude-mythos-preview"),
    CLAUDE_OPUS_4_6_20260205("claude-opus-4-6-20260205"),
    CLAUDE_OPUS_4_7_20260416("claude-opus-4-7-20260416"),
    CLAUDE_OPUS_5_5("claude-opus-5-5");

    private final String value;

    private static final Set<String> ALL_MODEL_IDS = Arrays.stream(values())
            .map(AnthropicModelName::getValue)
            .collect(Collectors.toUnmodifiableSet());

    public static Set<String> allModelIds() {
        return ALL_MODEL_IDS;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean isStructuredOutputSupported() {
        return false;
    }
}
