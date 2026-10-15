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
            new InputVariant("llama",
                    keys -> keys.hasAny("prompt_token_count", "generation_token_count"),
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
        var keys = input.keys();
        return builder(input)
                .inputTokens(keys.get("prompt_tokens"))
                .cacheReadInputTokens(keys.get("cache_read_input_tokens"))
                .cacheWriteInputTokens(keys.get("cache_creation_input_tokens"))
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
