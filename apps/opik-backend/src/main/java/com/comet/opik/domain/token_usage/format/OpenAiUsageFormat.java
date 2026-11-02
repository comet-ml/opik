package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.NativeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiNativeUsage;

import java.util.List;

/**
 * OpenAI usage, and the default for every provider without a format of its own. Every variant counts the cache, read
 * and written, inside the input unless the numbers show otherwise, so they differ only in key names.
 */
final class OpenAiUsageFormat implements UsageFormat {

    private static final List<InputVariant> INPUT_VARIANTS = List.of(
            new InputVariant("deepseek",
                    keys -> keys.has("prompt_tokens")
                            && keys.hasAny("prompt_cache_hit_tokens", "prompt_cache_miss_tokens"),
                    OpenAiUsageFormat::fromDeepSeek),
            new InputVariant("chat_completions",
                    keys -> keys.hasAny("prompt_tokens", "completion_tokens"),
                    OpenAiUsageFormat::fromChatCompletions),
            new InputVariant("responses",
                    keys -> keys.hasAny("input_tokens", "output_tokens"),
                    OpenAiUsageFormat::fromResponses),
            new InputVariant("openinference",
                    keys -> keys.hasAny("prompt", "completion"),
                    OpenAiUsageFormat::fromOpenInference));

    @Override
    public List<InputVariant> inputVariants() {
        return INPUT_VARIANTS;
    }

    private static NativeUsage fromDeepSeek(VariantInput input) {
        return chatCompletions(input, input.keys().get("prompt_cache_hit_tokens"), 0);
    }

    private static NativeUsage fromChatCompletions(VariantInput input) {
        // Bare cache_read_input_tokens comes from flat OTel spans, cache_read.input_tokens from OTel GenAI spans (whose
        // input_tokens OTel ingestion stores as prompt_tokens), bare cached_tokens from flattened detail leaves.
        // OpenRouter reports writes for the providers that bill them (Anthropic) in the prompt details. Gemini's
        // cached_content_token_count comes from Gemini usage the SDK logged under another provider.
        var keys = input.keys();
        return chatCompletions(input,
                keys.first("prompt_tokens_details.cached_tokens", "input_tokens_details.cached_tokens",
                        "cache_read_input_tokens", "cache_read.input_tokens", "cached_tokens", "cache_read_tokens",
                        "cached_content_token_count"),
                keys.first("prompt_tokens_details.cache_write_tokens", "prompt_tokens_details.cache_creation_tokens",
                        "cache_creation_input_tokens", "cache_creation.input_tokens", "cache_write_tokens",
                        "cache_creation_tokens"));
    }

    private static NativeUsage chatCompletions(VariantInput input, long cachedTokens, long cacheWriteTokens) {
        var keys = input.keys();
        var cacheWrites = CacheWrites.of(keys, cacheWriteTokens);
        long promptTokens = input.includingCache(keys.get("prompt_tokens"), cachedTokens + cacheWrites.total(),
                keys.get("completion_tokens"));
        return builder(input)
                .promptTokens(promptTokens)
                .cachedTokens(cachedTokens)
                .cacheWriteTokens(cacheWrites.total())
                .cacheWrite1hTokens(cacheWrites.oneHour())
                .audioPromptTokens(keys.get("prompt_tokens_details.audio_tokens"))
                .completionTokens(keys.get("completion_tokens"))
                .reasoningTokens(keys.first("completion_tokens_details.reasoning_tokens", "reasoning.output_tokens",
                        "reasoning_tokens"))
                .audioCompletionTokens(keys.get("completion_tokens_details.audio_tokens"))
                .totalTokens(keys.get("total_tokens"))
                .textPromptTokens(keys.find("prompt_tokens_details.text_tokens"))
                .textCompletionTokens(keys.find("completion_tokens_details.text_tokens"))
                .build();
    }

    private static NativeUsage fromResponses(VariantInput input) {
        var keys = input.keys();
        long cachedTokens = keys.first("input_tokens_details.cached_tokens", "cache_read.input_tokens",
                "cache_read_input_tokens", "cached_tokens");
        var cacheWrites = CacheWrites.of(keys,
                keys.first("cache_creation.input_tokens", "cache_creation_input_tokens"));
        long promptTokens = input.includingCache(keys.get("input_tokens"), cachedTokens + cacheWrites.total(),
                keys.get("output_tokens"));
        return builder(input)
                .promptTokens(promptTokens)
                .cachedTokens(cachedTokens)
                .cacheWriteTokens(cacheWrites.total())
                .cacheWrite1hTokens(cacheWrites.oneHour())
                .audioPromptTokens(keys.get("input_tokens_details.audio_tokens"))
                .completionTokens(keys.get("output_tokens"))
                .reasoningTokens(keys.first("output_tokens_details.reasoning_tokens", "reasoning.output_tokens",
                        "reasoning_tokens"))
                .audioCompletionTokens(keys.get("output_tokens_details.audio_tokens"))
                .totalTokens(keys.get("total_tokens"))
                .textPromptTokens(keys.find("input_tokens_details.text_tokens"))
                .textCompletionTokens(keys.find("output_tokens_details.text_tokens"))
                .build();
    }

    private static NativeUsage fromOpenInference(VariantInput input) {
        var keys = input.keys();
        long cachedTokens = keys.get("prompt_details.cache_read");
        long cacheWriteTokens = keys.get("prompt_details.cache_write");
        long promptTokens = input.includingCache(keys.get("prompt"), cachedTokens + cacheWriteTokens,
                keys.get("completion"));
        return builder(input)
                .promptTokens(promptTokens)
                .cachedTokens(cachedTokens)
                .cacheWriteTokens(cacheWriteTokens)
                .audioPromptTokens(keys.get("prompt_details.audio"))
                .completionTokens(keys.get("completion"))
                .reasoningTokens(keys.get("completion_details.reasoning"))
                .audioCompletionTokens(keys.get("completion_details.audio"))
                .totalTokens(keys.get("total"))
                .build();
    }

    private static OpenAiNativeUsage.OpenAiNativeUsageBuilder builder(VariantInput input) {
        return OpenAiNativeUsage.builder().provider(input.provider()).variant(input.variant());
    }
}
