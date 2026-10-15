package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.GeminiNativeUsage;
import com.comet.opik.domain.token_usage.model.NativeUsage;

import java.util.List;

/**
 * Gemini usage metadata: {@code prompt_token_count} includes the cache, and thinking tokens are reported outside the
 * candidates. Clients that report Gemini in OpenAI's shape put thinking inside the completion as reasoning tokens.
 */
final class GeminiUsageFormat implements UsageFormat {

    private static final List<InputVariant> INPUT_VARIANTS = List.of(
            new InputVariant("gemini_native",
                    keys -> keys.hasAny("prompt_token_count", "candidates_token_count"),
                    GeminiUsageFormat::fromNative),
            new InputVariant("openai_style",
                    keys -> keys.hasAny("prompt_tokens", "completion_tokens"),
                    GeminiUsageFormat::fromOpenAiStyle),
            // The OpenAI Agents SDK, and OTel ingestion once its renamed keys are restored.
            new InputVariant("responses_style",
                    keys -> keys.hasAny("input_tokens", "output_tokens"),
                    GeminiUsageFormat::fromResponsesStyle));

    @Override
    public List<InputVariant> inputVariants() {
        return INPUT_VARIANTS;
    }

    private static NativeUsage fromNative(VariantInput input) {
        var keys = input.keys();
        long promptTokenCount = keys.get("prompt_token_count");
        long cachedContentTokenCount = keys.get("cached_content_token_count");
        input.checkCacheWithinInput(promptTokenCount, cachedContentTokenCount);
        return builder(input)
                .promptTokenCount(promptTokenCount)
                .cachedContentTokenCount(cachedContentTokenCount)
                .toolUsePromptTokenCount(keys.get("tool_use_prompt_token_count"))
                .candidatesTokenCount(keys.get("candidates_token_count"))
                .thoughtsTokenCount(keys.get("thoughts_token_count"))
                .totalTokenCount(keys.get("total_token_count"))
                .build();
    }

    private static NativeUsage fromOpenAiStyle(VariantInput input) {
        var keys = input.keys();
        return fromCompletionWithReasoning(input, keys.get("prompt_tokens"),
                keys.first("cache_read_input_tokens", "prompt_tokens_details.cached_tokens"),
                keys.get("completion_tokens"),
                keys.first("completion_tokens_details.reasoning_tokens", "reasoning_tokens"));
    }

    private static NativeUsage fromResponsesStyle(VariantInput input) {
        var keys = input.keys();
        return fromCompletionWithReasoning(input, keys.get("input_tokens"),
                keys.first("input_tokens_details.cached_tokens", "cache_read.input_tokens", "cache_read_input_tokens"),
                keys.get("output_tokens"),
                keys.first("output_tokens_details.reasoning_tokens", "reasoning.output_tokens"));
    }

    private static NativeUsage fromCompletionWithReasoning(VariantInput input, long promptTokens, long cachedTokens,
            long completionTokens, long reasoningTokens) {
        input.checkCacheWithinInput(promptTokens, cachedTokens);
        long thoughts = Math.min(reasoningTokens, completionTokens);
        return builder(input)
                .promptTokenCount(promptTokens)
                .cachedContentTokenCount(cachedTokens)
                .candidatesTokenCount(completionTokens - thoughts)
                .thoughtsTokenCount(thoughts)
                .totalTokenCount(input.keys().get("total_tokens"))
                .build();
    }

    private static GeminiNativeUsage.GeminiNativeUsageBuilder builder(VariantInput input) {
        return GeminiNativeUsage.builder().provider(input.provider()).variant(input.variant());
    }
}
