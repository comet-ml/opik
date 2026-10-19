package com.comet.opik.domain.token_usage.model;

/**
 * A span's usage in its provider's official format: only that provider's own fields, carrying the provider's own
 * meaning whatever client logged them. Cached tokens are already resolved, so for example Anthropic's
 * {@code inputTokens} never includes the cache and OpenAI's {@code promptTokens} always does.
 */
public sealed interface NativeUsage
        permits AnthropicNativeUsage, OpenAiNativeUsage, GeminiNativeUsage, BedrockConverseNativeUsage {

    String provider();

    /** The input variant of the provider's format that matched the original usage, for metrics. */
    String variant();

    OpenAiLikeUsage toOpenAiLike();
}
