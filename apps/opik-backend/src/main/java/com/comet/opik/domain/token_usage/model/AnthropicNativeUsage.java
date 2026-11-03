package com.comet.opik.domain.token_usage.model;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record AnthropicNativeUsage(
        @NonNull String provider,
        @NonNull String variant,
        long inputTokens,
        long cacheReadInputTokens,
        long cacheCreation5mInputTokens,
        long cacheCreation1hInputTokens,
        long outputTokens) implements NativeUsage {

    @Override
    public OpenAiLikeUsage toOpenAiLike() {
        // Anthropic reports cached tokens outside input_tokens, so the prompt adds them back.
        long promptTokens = inputTokens + cacheReadInputTokens + cacheCreation5mInputTokens
                + cacheCreation1hInputTokens;
        return OpenAiLikeUsage.of(promptTokens, outputTokens, promptTokens + outputTokens, cacheReadInputTokens);
    }
}
