package com.comet.opik.domain.token_usage.model;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

/**
 * OpenAI's usage, in Chat Completions names. Responses API usage maps into it directly ({@code input_tokens} is
 * {@code promptTokens}, {@code output_tokens} is {@code completionTokens}). It's also the format of every provider
 * without one of its own, since most serve OpenAI-compatible APIs.
 *
 * @param cacheWriteTokens     the prompt tokens written to the cache, for the providers behind OpenAI-compatible APIs
 *                             that bill writes (Anthropic through OpenRouter). Part of the prompt, like the reads.
 * @param cacheWrite1hTokens   the part of {@code cacheWriteTokens} written with a 1-hour TTL.
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
        long cacheWriteTokens,
        long cacheWrite1hTokens,
        long audioPromptTokens,
        long completionTokens,
        long reasoningTokens,
        long audioCompletionTokens,
        long totalTokens,
        @Nullable Long textPromptTokens,
        @Nullable Long textCompletionTokens) implements NativeUsage {

    @Override
    public OpenAiLikeUsage toOpenAiLike() {
        // The reported total wins when there is one, as it does in the SDK, so the two agree during the overlap. A client
        // that left the cache out of the input may have left it out of the total too, though.
        long total = Math.max(totalTokens, promptTokens + completionTokens);
        return OpenAiLikeUsage.of(promptTokens, completionTokens, total, cachedTokens);
    }
}
