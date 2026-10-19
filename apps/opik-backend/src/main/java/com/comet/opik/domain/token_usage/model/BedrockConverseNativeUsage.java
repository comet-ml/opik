package com.comet.opik.domain.token_usage.model;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record BedrockConverseNativeUsage(
        @NonNull String provider,
        @NonNull String variant,
        long inputTokens,
        long cacheReadInputTokens,
        long cacheWriteInputTokens,
        long outputTokens) implements NativeUsage {

    @Override
    public OpenAiLikeUsage toOpenAiLike() {
        // Bedrock reports cached tokens outside inputTokens, so the prompt adds them back.
        long promptTokens = inputTokens + cacheReadInputTokens + cacheWriteInputTokens;
        return OpenAiLikeUsage.of(promptTokens, outputTokens, promptTokens + outputTokens, cacheReadInputTokens);
    }
}
