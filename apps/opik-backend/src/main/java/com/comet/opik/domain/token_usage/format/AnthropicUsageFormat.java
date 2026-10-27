package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.AnthropicNativeUsage;
import com.comet.opik.domain.token_usage.model.NativeUsage;

import java.util.List;

/**
 * Anthropic Messages usage: {@code input_tokens} excludes the cache, which is reported in its own read and write
 * counters. Checked in order; the OTel GenAI variant comes first because it shares {@code input_tokens} with the
 * native one but counts the cache inside it. OTel ingestion stores {@code input_tokens} and {@code output_tokens} as
 * {@code prompt_tokens} and {@code completion_tokens}, so the variants that clients other than the SDK send read
 * either name.
 */
final class AnthropicUsageFormat implements UsageFormat {

    private static final List<InputVariant> INPUT_VARIANTS = List.of(
            new InputVariant("otel_genai",
                    keys -> keys.hasAny("input_tokens", "prompt_tokens")
                            && keys.hasAny("cache_read.input_tokens", "cache_creation.input_tokens"),
                    AnthropicUsageFormat::fromOtelGenAi),
            // OpenAI Responses usage (the OpenAI Agents SDK running Claude): its input includes the cache.
            new InputVariant("openai_responses",
                    keys -> keys.has("input_tokens") && keys.has("input_tokens_details.cached_tokens"),
                    AnthropicUsageFormat::fromOpenAiResponses),
            new InputVariant("anthropic_native",
                    keys -> keys.has("input_tokens") || keys.hasListItems("iterations"),
                    AnthropicUsageFormat::fromNative),
            // Also the short cache names of Pydantic AI (cache_write_tokens) and Claude Code (cache_creation_tokens).
            new InputVariant("litellm",
                    keys -> keys.has("prompt_tokens") && keys.hasAny("cache_read_input_tokens",
                            "prompt_tokens_details.cached_tokens", "cache_creation_input_tokens", "cache_read_tokens",
                            "cache_write_tokens", "cache_creation_tokens"),
                    AnthropicUsageFormat::fromLiteLlm),
            new InputVariant("openinference",
                    keys -> keys.has("prompt"),
                    AnthropicUsageFormat::fromOpenInference),
            new InputVariant("totals_only",
                    keys -> keys.hasAny("prompt_tokens", "completion_tokens"),
                    AnthropicUsageFormat::fromTotals));

    @Override
    public List<InputVariant> inputVariants() {
        return INPUT_VARIANTS;
    }

    private static NativeUsage fromOtelGenAi(VariantInput input) {
        var keys = input.keys();
        long cacheRead = keys.get("cache_read.input_tokens");
        long cacheWrite = keys.get("cache_creation.input_tokens");
        long outputTokens = keys.first("output_tokens", "completion_tokens");
        return builder(input)
                .inputTokens(input.excludingCache(keys.first("input_tokens", "prompt_tokens"), cacheRead + cacheWrite,
                        outputTokens))
                .cacheReadInputTokens(cacheRead)
                .cacheCreation5mInputTokens(cacheWrite)
                .outputTokens(outputTokens)
                .build();
    }

    private static NativeUsage fromOpenAiResponses(VariantInput input) {
        var keys = input.keys();
        long cacheRead = keys.get("input_tokens_details.cached_tokens");
        return builder(input)
                .inputTokens(input.excludingCache(keys.get("input_tokens"), cacheRead, keys.get("output_tokens")))
                .cacheReadInputTokens(cacheRead)
                .outputTokens(keys.get("output_tokens"))
                .build();
    }

    private static NativeUsage fromNative(VariantInput input) {
        var keys = input.keys();
        if (keys.hasListItems("iterations")) {
            // Compaction: the top-level counts cover only the message iterations, while every iteration is billed.
            return builder(input)
                    .inputTokens(keys.sumOverListItems("iterations", "input_tokens"))
                    .cacheReadInputTokens(keys.sumOverListItems("iterations", "cache_read_input_tokens"))
                    .cacheCreation5mInputTokens(keys.sumOverListItems("iterations", "cache_creation_input_tokens"))
                    .outputTokens(keys.sumOverListItems("iterations", "output_tokens"))
                    .build();
        }
        // Bedrock Converse names the writes cacheWriteInputTokens.
        var cacheWrites = CacheWrites.of(keys, "cache_creation.ephemeral_5m_input_tokens",
                "cache_creation.ephemeral_1h_input_tokens",
                keys.first("cache_creation_input_tokens", "cache_write_input_tokens"));
        return builder(input)
                .inputTokens(keys.get("input_tokens"))
                .cacheReadInputTokens(keys.get("cache_read_input_tokens"))
                .cacheCreation5mInputTokens(cacheWrites.fiveMinutes())
                .cacheCreation1hInputTokens(cacheWrites.oneHour())
                .outputTokens(keys.get("output_tokens"))
                .build();
    }

    /**
     * {@code prompt_tokens} next to Anthropic's cache counters. LiteLLM's includes the cache, and LiteLLM also reports
     * the prompt's details. Other clients (OTel ingestion, the online-evaluation judge) only renamed Anthropic's
     * {@code input_tokens}, which excludes it.
     */
    private static NativeUsage fromLiteLlm(VariantInput input) {
        var keys = input.keys();
        // A client that saw no cache reads may still send its zero next to another client's count of them.
        long cacheRead = Math.max(keys.get("cache_read_input_tokens"),
                Math.max(keys.get("prompt_tokens_details.cached_tokens"), keys.get("cache_read_tokens")));
        var cacheWrites = CacheWrites.of(keys,
                keys.first("cache_creation_input_tokens", "cache_write_tokens", "cache_creation_tokens"));
        long promptTokens = keys.get("prompt_tokens");
        return builder(input)
                .inputTokens(keys.hasAny("prompt_tokens_details.cached_tokens",
                        "prompt_tokens_details.cache_creation_tokens")
                                ? input.excludingCache(promptTokens, cacheRead + cacheWrites.total(),
                                        keys.get("completion_tokens"))
                                : promptTokens)
                .cacheReadInputTokens(cacheRead)
                .cacheCreation5mInputTokens(cacheWrites.fiveMinutes())
                .cacheCreation1hInputTokens(cacheWrites.oneHour())
                .outputTokens(keys.get("completion_tokens"))
                .build();
    }

    private static NativeUsage fromOpenInference(VariantInput input) {
        var keys = input.keys();
        long cacheRead = keys.get("prompt_details.cache_read");
        long cacheWrite = keys.get("prompt_details.cache_write");
        return builder(input)
                .inputTokens(input.excludingCache(keys.get("prompt"), cacheRead + cacheWrite, keys.get("completion")))
                .cacheReadInputTokens(cacheRead)
                .cacheCreation5mInputTokens(cacheWrite)
                .outputTokens(keys.get("completion"))
                .build();
    }

    private static NativeUsage fromTotals(VariantInput input) {
        return builder(input)
                .inputTokens(input.keys().get("prompt_tokens"))
                .outputTokens(input.keys().get("completion_tokens"))
                .build();
    }

    private static AnthropicNativeUsage.AnthropicNativeUsageBuilder builder(VariantInput input) {
        return AnthropicNativeUsage.builder().provider(input.provider()).variant(input.variant());
    }
}
