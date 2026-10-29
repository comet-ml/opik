package com.comet.opik.domain.token_usage.model;

import jakarta.annotation.Nullable;
import lombok.Builder;

/**
 * The OpenAI-style view of a span's usage that the frontend, the {@code usage.*} filters and the trace totals read.
 * Derived from the native usage, or the client's own totals when there is none; a total the client didn't send stays
 * {@code null}.
 */
@Builder(toBuilder = true)
public record OpenAiLikeUsage(
        @Nullable Long promptTokens,
        @Nullable Long completionTokens,
        @Nullable Long totalTokens,
        @Nullable Long cachedPromptTokens) {

    public static final String PROMPT_TOKENS = "prompt_tokens";
    public static final String COMPLETION_TOKENS = "completion_tokens";
    public static final String TOTAL_TOKENS = "total_tokens";

    public static final OpenAiLikeUsage EMPTY = new OpenAiLikeUsage(null, null, null, null);

    public static OpenAiLikeUsage of(long promptTokens, long completionTokens, long totalTokens,
            long cachedPromptTokens) {
        return new OpenAiLikeUsage(promptTokens, completionTokens, totalTokens, cachedPromptTokens);
    }
}
