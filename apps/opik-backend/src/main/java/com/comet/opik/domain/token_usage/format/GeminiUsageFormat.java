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
            // The OpenAI Agents SDK.
            new InputVariant("responses_style",
                    keys -> keys.hasAny("input_tokens", "output_tokens"),
                    GeminiUsageFormat::fromResponsesStyle));

    @Override
    public List<InputVariant> inputVariants() {
        return INPUT_VARIANTS;
    }

    private static NativeUsage fromNative(VariantInput input) {
        var keys = input.keys();
        long cachedContentTokenCount = keys.get("cached_content_token_count");
        long toolUsePromptTokenCount = keys.get("tool_use_prompt_token_count");
        long thoughtsTokenCount = keys.get("thoughts_token_count");
        // Counts Gemini left out come from the client's totals: its prompt includes the tool results, and its
        // completion the thoughts.
        long reportedPromptTokenCount = keys.has("prompt_token_count")
                ? keys.get("prompt_token_count")
                : Math.max(0, keys.get("prompt_tokens") - toolUsePromptTokenCount);
        long candidatesTokenCount = keys.has("candidates_token_count")
                ? keys.get("candidates_token_count")
                : Math.max(0, keys.get("completion_tokens") - thoughtsTokenCount);
        // The cache covers the tool results fed back as input too. Gemini's own total also counts thoughts, so only the
        // cache-larger-than-input check applies.
        long promptTokenCount = input.includingCache(reportedPromptTokenCount + toolUsePromptTokenCount,
                cachedContentTokenCount, null) - toolUsePromptTokenCount;
        return builder(input)
                .promptTokenCount(promptTokenCount)
                .cachedContentTokenCount(cachedContentTokenCount)
                .toolUsePromptTokenCount(toolUsePromptTokenCount)
                .candidatesTokenCount(candidatesTokenCount)
                .thoughtsTokenCount(thoughtsTokenCount)
                .totalTokenCount(keys.get("total_token_count"))
                .build();
    }

    private static NativeUsage fromOpenAiStyle(VariantInput input) {
        var keys = input.keys();
        // cache_read.input_tokens and reasoning.output_tokens come from OTel GenAI spans, whose input_tokens and
        // output_tokens OTel ingestion stores as prompt_tokens and completion_tokens.
        return fromCompletionWithReasoning(input, keys.get("prompt_tokens"),
                keys.first("cache_read_input_tokens", "prompt_tokens_details.cached_tokens", "cache_read.input_tokens"),
                keys.get("completion_tokens"),
                keys.first("completion_tokens_details.reasoning_tokens", "reasoning_tokens",
                        "reasoning.output_tokens"));
    }

    private static NativeUsage fromResponsesStyle(VariantInput input) {
        var keys = input.keys();
        return fromCompletionWithReasoning(input, keys.get("input_tokens"),
                keys.first("input_tokens_details.cached_tokens", "cache_read.input_tokens", "cache_read_input_tokens"),
                keys.get("output_tokens"),
                keys.first("output_tokens_details.reasoning_tokens", "reasoning.output_tokens"));
    }

    private static NativeUsage fromCompletionWithReasoning(VariantInput input, long reportedPromptTokens,
            long cachedTokens, long completionTokens, long reasoningTokens) {
        long promptTokens = input.includingCache(reportedPromptTokens, cachedTokens, completionTokens);
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
