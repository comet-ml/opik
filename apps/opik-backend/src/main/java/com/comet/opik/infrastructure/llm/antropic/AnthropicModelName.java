package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.RequiredArgsConstructor;

/**
 * This information is taken from <a href="https://docs.anthropic.com/en/docs/about-claude/models">Anthropic docs</a>
 */
@RequiredArgsConstructor
public enum AnthropicModelName implements StructuredOutputSupported {
    CLAUDE_OPUS_4_6("claude-opus-4-6"),
    CLAUDE_OPUS_4_5("claude-opus-4-5-20251101"),
    CLAUDE_OPUS_4_1("claude-opus-4-1-20250805"),
    CLAUDE_OPUS_4("claude-opus-4-20250514"),
    CLAUDE_SONNET_4_6("claude-sonnet-4-6"),
    CLAUDE_SONNET_4_5("claude-sonnet-4-5"),
    CLAUDE_SONNET_4("claude-sonnet-4-20250514"),
    CLAUDE_SONNET_3_7("claude-3-7-sonnet-20250219"),
    CLAUDE_HAIKU_4_5("claude-haiku-4-5-20251001"),
    ;

    private final String value;

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean isStructuredOutputSupported() {
        return false;
    }
}
