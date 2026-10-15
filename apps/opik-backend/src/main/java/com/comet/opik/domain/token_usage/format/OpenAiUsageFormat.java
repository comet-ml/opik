package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.NativeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiNativeUsage;

import java.util.List;

/**
 * OpenAI usage, and the default for every provider without a format of its own. Every variant counts the cache inside
 * the input, so they differ only in key names.
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
        return chatCompletions(input, input.keys().get("prompt_cache_hit_tokens"));
    }

    private static NativeUsage fromChatCompletions(VariantInput input) {
        // Bare cache_read_input_tokens comes from flat OTel spans, bare cached_tokens from flattened detail leaves.
        return chatCompletions(input, input.keys().first("prompt_tokens_details.cached_tokens",
                "cache_read_input_tokens", "cached_tokens"));
    }

    private static NativeUsage chatCompletions(VariantInput input, long cachedTokens) {
        var keys = input.keys();
        long promptTokens = keys.get("prompt_tokens");
        input.checkCacheWithinInput(promptTokens, cachedTokens);
        return builder(input)
                .promptTokens(promptTokens)
                .cachedTokens(cachedTokens)
                .audioPromptTokens(keys.get("prompt_tokens_details.audio_tokens"))
                .completionTokens(keys.get("completion_tokens"))
                .reasoningTokens(keys.first("completion_tokens_details.reasoning_tokens", "reasoning_tokens"))
                .audioCompletionTokens(keys.get("completion_tokens_details.audio_tokens"))
                .totalTokens(keys.get("total_tokens"))
                .textPromptTokens(keys.find("prompt_tokens_details.text_tokens"))
                .textCompletionTokens(keys.find("completion_tokens_details.text_tokens"))
                .build();
    }

    private static NativeUsage fromResponses(VariantInput input) {
        var keys = input.keys();
        long promptTokens = keys.get("input_tokens");
        long cachedTokens = keys.first("input_tokens_details.cached_tokens", "cache_read.input_tokens",
                "cache_read_input_tokens", "cached_tokens");
        input.checkCacheWithinInput(promptTokens, cachedTokens);
        return builder(input)
                .promptTokens(promptTokens)
                .cachedTokens(cachedTokens)
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
        long promptTokens = keys.get("prompt");
        long cachedTokens = keys.get("prompt_details.cache_read");
        input.checkCacheWithinInput(promptTokens, cachedTokens);
        return builder(input)
                .promptTokens(promptTokens)
                .cachedTokens(cachedTokens)
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
