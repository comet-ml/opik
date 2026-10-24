package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.BedrockConverseNativeUsage;
import com.comet.opik.domain.token_usage.model.NativeUsage;

import java.util.List;

/**
 * Bedrock Converse usage: {@code inputTokens} excludes the cache. Names match ignoring case, so the Converse names and
 * the InvokeModel bodies that use Anthropic's snake_case spelling of them fall into the same variant.
 */
final class BedrockConverseUsageFormat implements UsageFormat {

    private static final List<InputVariant> INPUT_VARIANTS = List.of(
            new InputVariant("converse",
                    keys -> keys.hasAny("input_tokens", "output_tokens"),
                    BedrockConverseUsageFormat::fromConverse),
            // generation_token_count is Llama's own; prompt_token_count alone is Gemini's too.
            new InputVariant("llama",
                    keys -> keys.has("generation_token_count"),
                    BedrockConverseUsageFormat::fromLlama),
            new InputVariant("litellm",
                    keys -> keys.has("prompt_tokens")
                            && keys.hasAny("cache_read_input_tokens", "cache_creation_input_tokens"),
                    BedrockConverseUsageFormat::fromLiteLlm),
            new InputVariant("totals_only",
                    keys -> keys.hasAny("prompt_tokens", "completion_tokens"),
                    BedrockConverseUsageFormat::fromTotals));

    @Override
    public List<InputVariant> inputVariants() {
        return INPUT_VARIANTS;
    }

    private static NativeUsage fromConverse(VariantInput input) {
        var keys = input.keys();
        return builder(input)
                .inputTokens(keys.get("input_tokens"))
                .cacheReadInputTokens(keys.get("cache_read_input_tokens"))
                .cacheWriteInputTokens(keys.first("cache_write_input_tokens", "cache_creation_input_tokens"))
                .outputTokens(keys.get("output_tokens"))
                .build();
    }

    private static NativeUsage fromLlama(VariantInput input) {
        return builder(input)
                .inputTokens(input.keys().get("prompt_token_count"))
                .outputTokens(input.keys().get("generation_token_count"))
                .build();
    }

    private static NativeUsage fromLiteLlm(VariantInput input) {
        // Measured in production: LiteLLM's Bedrock prompt_tokens is below the cache on 98% of rows, so it excludes it.
        // The LiteLLM versions that include it also report the prompt's details.
        var keys = input.keys();
        long cacheRead = keys.get("cache_read_input_tokens");
        long cacheWrite = keys.get("cache_creation_input_tokens");
        long promptTokens = keys.get("prompt_tokens");
        return builder(input)
                .inputTokens(keys.hasAny("prompt_tokens_details.cached_tokens",
                        "prompt_tokens_details.cache_creation_tokens")
                                ? input.excludingCache(promptTokens, cacheRead + cacheWrite,
                                        keys.get("completion_tokens"))
                                : promptTokens)
                .cacheReadInputTokens(cacheRead)
                .cacheWriteInputTokens(cacheWrite)
                .outputTokens(keys.get("completion_tokens"))
                .build();
    }

    private static NativeUsage fromTotals(VariantInput input) {
        return builder(input)
                .inputTokens(input.keys().get("prompt_tokens"))
                .outputTokens(input.keys().get("completion_tokens"))
                .build();
    }

    private static BedrockConverseNativeUsage.BedrockConverseNativeUsageBuilder builder(VariantInput input) {
        return BedrockConverseNativeUsage.builder().provider(input.provider()).variant(input.variant());
    }
}
