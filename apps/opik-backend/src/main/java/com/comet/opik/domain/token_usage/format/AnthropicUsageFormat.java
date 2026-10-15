package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.AnthropicNativeUsage;
import com.comet.opik.domain.token_usage.model.NativeUsage;

import java.util.List;

/**
 * Anthropic Messages usage: {@code input_tokens} excludes the cache, which is reported in its own read and write
 * counters. Checked in order; the OTel GenAI variant comes first because it shares {@code input_tokens} with the
 * native one but counts the cache inside it.
 */
final class AnthropicUsageFormat implements UsageFormat {

    private static final List<InputVariant> INPUT_VARIANTS = List.of(
            new InputVariant("otel_genai",
                    keys -> keys.has("input_tokens")
                            && keys.hasAny("cache_read.input_tokens", "cache_creation.input_tokens"),
                    AnthropicUsageFormat::fromOtelGenAi),
            new InputVariant("anthropic_native",
                    keys -> keys.has("input_tokens") || keys.hasListItems("iterations"),
                    AnthropicUsageFormat::fromNative),
            new InputVariant("litellm",
                    keys -> keys.has("prompt_tokens") && keys.hasAny("cache_read_input_tokens",
                            "prompt_tokens_details.cached_tokens", "cache_creation_input_tokens"),
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
        return builder(input)
                .inputTokens(input.excludingCache(keys.get("input_tokens"), cacheRead + cacheWrite))
                .cacheReadInputTokens(cacheRead)
                .cacheCreation5mInputTokens(cacheWrite)
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
        var cacheWrites = CacheWrites.of(keys, "cache_creation.ephemeral_5m_input_tokens",
                "cache_creation.ephemeral_1h_input_tokens", "cache_creation_input_tokens");
        return builder(input)
                .inputTokens(keys.get("input_tokens"))
                .cacheReadInputTokens(keys.get("cache_read_input_tokens"))
                .cacheCreation5mInputTokens(cacheWrites.fiveMinutes())
                .cacheCreation1hInputTokens(cacheWrites.oneHour())
                .outputTokens(keys.get("output_tokens"))
                .build();
    }

    private static NativeUsage fromLiteLlm(VariantInput input) {
        var keys = input.keys();
        long cacheRead = keys.first("cache_read_input_tokens", "prompt_tokens_details.cached_tokens");
        var cacheWrites = CacheWrites.of(keys, "cache_creation_token_details.ephemeral_5m_input_tokens",
                "cache_creation_token_details.ephemeral_1h_input_tokens", "cache_creation_input_tokens");
        return builder(input)
                .inputTokens(input.excludingCache(keys.get("prompt_tokens"), cacheRead + cacheWrites.total()))
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
                .inputTokens(input.excludingCache(keys.get("prompt"), cacheRead + cacheWrite))
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

    /** Anthropic splits cache writes by TTL; without the split, every write counts as a 5-minute write. */
    private record CacheWrites(long fiveMinutes, long oneHour) {

        static CacheWrites of(UsageKeys keys, String fiveMinutesKey, String oneHourKey, String totalKey) {
            if (keys.hasAny(fiveMinutesKey, oneHourKey)) {
                return new CacheWrites(keys.get(fiveMinutesKey), keys.get(oneHourKey));
            }
            return new CacheWrites(keys.get(totalKey), 0);
        }

        long total() {
            return fiveMinutes + oneHour;
        }
    }
}
