package com.comet.opik.domain.token_usage.model;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

/**
 * OpenAI's usage, in Chat Completions names. Responses API usage maps into it directly ({@code input_tokens} is
 * {@code promptTokens}, {@code output_tokens} is {@code completionTokens}). It's also the format of every provider
 * without one of its own, since most serve OpenAI-compatible APIs.
 *
 * @param textPromptTokens     the text part of the prompt when the provider reports it (OpenAI Realtime), which is
 *                             more exact than subtracting the cached and audio tokens, as those can overlap.
 * @param textCompletionTokens the text part of the completion, when reported.
 */
@Builder(toBuilder = true)
public record OpenAiNativeUsage(
        @NonNull String provider,
        @NonNull String variant,
        long promptTokens,
        long cachedTokens,
        long audioPromptTokens,
        long completionTokens,
        long reasoningTokens,
        long audioCompletionTokens,
        long totalTokens,
        @Nullable Long textPromptTokens,
        @Nullable Long textCompletionTokens) implements NativeUsage {

    @Override
    public OpenAiLikeUsage toOpenAiLike() {
        // The reported total wins when there is one, as it does in the SDK, so the two agree during the overlap.
        long total = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
        return OpenAiLikeUsage.of(promptTokens, completionTokens, total, cachedTokens);
    }
}
