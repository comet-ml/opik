package com.comet.opik.domain.llmproviders;

import lombok.RequiredArgsConstructor;

/**
 * This information is taken from <a href="https://docs.anthropic.com/en/docs/about-claude/models">Anthropic docs</a>
 */
@RequiredArgsConstructor
public enum AnthropicModelName {
    CLAUDE_3_5_SONNET_LATEST("claude-3-5-sonnet-latest"),
    CLAUDE_3_5_SONNET_20241022("claude-3-5-sonnet-20241022"),
    CLAUDE_3_5_HAIKU_LATEST("claude-3-5-haiku-latest"),
    CLAUDE_3_5_HAIKU_20241022("claude-3-5-haiku-20241022"),
    CLAUDE_3_5_SONNET_20240620("claude-3-5-sonnet-20240620"),
    CLAUDE_3_OPUS_LATEST("claude-3-opus-latest"),
    CLAUDE_3_OPUS_20240229("claude-3-opus-20240229"),
    CLAUDE_3_SONNET_20240229("claude-3-sonnet-20240229"),
    CLAUDE_3_HAIKU_20240307("claude-3-haiku-20240307"),
    ;

    private final String value;

    @Override
    public String toString() {
        return value;
    }
}
