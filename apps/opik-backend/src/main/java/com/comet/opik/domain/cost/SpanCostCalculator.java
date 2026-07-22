package com.comet.opik.domain.cost;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.util.Map;

@UtilityClass
class SpanCostCalculator {
    private static final String VIDEO_DURATION_KEY = "video_duration_seconds";
    private static final String ORIGINAL_INPUT_CHARACTERS_KEY = "original_usage.input_characters";
    private static final String INPUT_CHARACTERS_KEY = "input_characters";
    // OTel GenAI semantic convention keys (bare, without original_usage. prefix)
    private static final String CACHE_READ_INPUT_TOKENS_KEY = "cache_read_input_tokens";
    private static final String CACHE_CREATION_INPUT_TOKENS_KEY = "cache_creation_input_tokens";

    public static BigDecimal textGenerationCost(@NonNull ModelPrice modelPrice, @NonNull Map<String, Integer> usage) {
        int promptTokens = usage.getOrDefault("prompt_tokens", 0);
        // Audio tokens (OpenAI realtime / audio-preview models) are billed at a separate rate;
        // SDK 1.6.0+ logs them under original_usage.prompt_tokens_details.audio_tokens,
        // with the bare OTel key as a fallback.
        int audioInputTokens = usage.getOrDefault("original_usage.prompt_tokens_details.audio_tokens",
                usage.getOrDefault("prompt_tokens_details.audio_tokens", 0));

        BigDecimal inputAudioRate = modelPrice.inputAudioTokenPrice();
        int nonAudioPromptTokens = inputAudioRate.compareTo(BigDecimal.ZERO) > 0
                ? Math.max(0, promptTokens - audioInputTokens)
                : promptTokens;

        int completionTokens = usage.getOrDefault("completion_tokens", 0);
        // Audio output tokens (OpenAI audio-preview / realtime) likewise carry a separate rate;
        // they're nested under original_usage.completion_tokens_details.audio_tokens.
        int audioOutputTokens = usage.getOrDefault("original_usage.completion_tokens_details.audio_tokens",
                usage.getOrDefault("completion_tokens_details.audio_tokens", 0));

        BigDecimal outputAudioRate = modelPrice.outputAudioTokenPrice();
        int nonAudioCompletionTokens = outputAudioRate.compareTo(BigDecimal.ZERO) > 0
                ? Math.max(0, completionTokens - audioOutputTokens)
                : completionTokens;

        return modelPrice.effectiveInputPrice(promptTokens).multiply(BigDecimal.valueOf(nonAudioPromptTokens))
                .add(inputAudioRate.multiply(BigDecimal.valueOf(audioInputTokens)))
                .add(modelPrice.effectiveOutputPrice(promptTokens)
                        .multiply(BigDecimal.valueOf(nonAudioCompletionTokens)))
                .add(outputAudioRate.multiply(BigDecimal.valueOf(audioOutputTokens)));
    }

    public static BigDecimal textGenerationWithCacheCostOpenAI(@NonNull ModelPrice modelPrice,
            @NonNull Map<String, Integer> usage) {

        // In OpenAI usage format, input tokens includes the cached input tokens, so we need to substract them to compute the correct input token count
        // Don't generalize yet as other providers seems to separate the cached tokens from non-cached tokens

        // Get the input tokens (SDK version below 1.6.0 logged prompt_tokens, while 1.6.0+ logged original_usage.prompt_tokens)
        int inputTokens = usage.getOrDefault("original_usage.prompt_tokens", usage.getOrDefault("prompt_tokens", 0));
        // Keep the total prompt-token count for tier evaluation: which above_NNNk rate applies is
        // decided on the whole prompt, not on the post-cache-subtraction remainder.
        int totalPromptTokens = inputTokens;

        // Get the cached read input tokens; fall back to OTel bare key for LiteLLM/OTel spans
        int cachedReadInputTokens = usage.getOrDefault("original_usage.prompt_tokens_details.cached_tokens",
                usage.getOrDefault("original_usage.input_tokens_details.cached_tokens",
                        usage.getOrDefault(CACHE_READ_INPUT_TOKENS_KEY, 0)));

        // Audio input tokens (OpenAI realtime models like gpt-4o-realtime-preview, gpt-realtime)
        // are billed at a separate rate when the model publishes input_cost_per_audio_token.
        // SDK 1.6.0+ logs them under original_usage.prompt_tokens_details.audio_tokens, with the
        // bare OTel key as a fallback.
        int audioInputTokens = usage.getOrDefault("original_usage.prompt_tokens_details.audio_tokens",
                usage.getOrDefault("prompt_tokens_details.audio_tokens", 0));
        BigDecimal inputAudioRate = modelPrice.inputAudioTokenPrice();

        // When the payload carries prompt_tokens_details.text_tokens explicitly (OpenAI Realtime
        // publishes it, and LiteLLM's _calculate_input_cost consumes it directly), use it as the
        // pure-text bucket. This avoids over-subtracting when cached and audio tokens overlap on
        // realtime models — see baz-reviewer's note on the double-charge risk.
        // Falls back to substracting cached + audio for the pre-realtime code path.
        Integer explicitInputTextTokens = usage.getOrDefault(
                "original_usage.prompt_tokens_details.text_tokens",
                usage.get("prompt_tokens_details.text_tokens"));
        if (explicitInputTextTokens != null) {
            inputTokens = explicitInputTextTokens;
        } else {
            if (cachedReadInputTokens > 0) {
                inputTokens = Math.max(0, inputTokens - cachedReadInputTokens);
            }
            if (inputAudioRate.compareTo(BigDecimal.ZERO) > 0) {
                inputTokens = Math.max(0, inputTokens - audioInputTokens);
            }
        }

        // Get the output tokens (SDK version below 1.6.0 logged completion_tokens, while 1.6.0+ logged original_usage.completion_tokens)
        int outputTokens = usage.getOrDefault("original_usage.completion_tokens",
                usage.getOrDefault("completion_tokens", 0));

        // Audio output tokens carry their own rate via output_cost_per_audio_token; same fallback shape.
        int audioOutputTokens = usage.getOrDefault("original_usage.completion_tokens_details.audio_tokens",
                usage.getOrDefault("completion_tokens_details.audio_tokens", 0));
        BigDecimal outputAudioRate = modelPrice.outputAudioTokenPrice();

        // Same explicit-text-tokens preference on the completion side.
        Integer explicitOutputTextTokens = usage.getOrDefault(
                "original_usage.completion_tokens_details.text_tokens",
                usage.get("completion_tokens_details.text_tokens"));
        if (explicitOutputTextTokens != null) {
            outputTokens = explicitOutputTextTokens;
        } else if (outputAudioRate.compareTo(BigDecimal.ZERO) > 0) {
            outputTokens = Math.max(0, outputTokens - audioOutputTokens);
        }

        return modelPrice.effectiveInputPrice(totalPromptTokens).multiply(BigDecimal.valueOf(inputTokens))
                .add(inputAudioRate.multiply(BigDecimal.valueOf(audioInputTokens)))
                .add(modelPrice.effectiveOutputPrice(totalPromptTokens).multiply(BigDecimal.valueOf(outputTokens)))
                .add(outputAudioRate.multiply(BigDecimal.valueOf(audioOutputTokens)))
                .add(modelPrice.effectiveCacheReadInputTokenPrice(totalPromptTokens)
                        .multiply(BigDecimal.valueOf(cachedReadInputTokens)));
    }

    public static BigDecimal textGenerationWithCacheCostAnthropic(@NonNull ModelPrice modelPrice,
            @NonNull Map<String, Integer> usage) {
        return textGenerationWithCachedTokensNotIncludedInCost(modelPrice, usage, "original_usage.input_tokens",
                "original_usage.output_tokens", "original_usage.cache_read_input_tokens",
                "original_usage.cache_creation_input_tokens");
    }

    public static BigDecimal textGenerationWithCacheCostBedrock(@NonNull ModelPrice modelPrice,
            @NonNull Map<String, Integer> usage) {
        return textGenerationWithCachedTokensNotIncludedInCost(modelPrice, usage, "original_usage.inputTokens",
                "original_usage.outputTokens", "original_usage.cacheReadInputTokens",
                "original_usage.cacheWriteInputTokens");
    }

    public static BigDecimal textGenerationWithCacheCostGoogle(@NonNull ModelPrice modelPrice,
            @NonNull Map<String, Integer> usage) {

        // In Google/Gemini usage format, prompt_token_count already includes the cached (context-cache)
        // tokens, so we subtract them to compute the non-cached input token count (similar to OpenAI).

        // Get the input tokens (prompt_token_count); fall back to the normalized prompt_tokens key
        int inputTokens = usage.getOrDefault("original_usage.prompt_token_count",
                usage.getOrDefault("prompt_tokens", 0));

        // Get the cached read tokens (cached_content_token_count); fall back to OTel bare key for LiteLLM/OTel spans
        int cachedReadInputTokens = usage.getOrDefault("original_usage.cached_content_token_count",
                usage.getOrDefault(CACHE_READ_INPUT_TOKENS_KEY, 0));

        // If we got cached tokens, subtract them from the input tokens count
        if (cachedReadInputTokens > 0) {
            inputTokens = Math.max(0, inputTokens - cachedReadInputTokens);
        }

        // Get the output tokens (completion_tokens includes reasoning/thought tokens); fall back to OTel key
        int outputTokens = usage.getOrDefault("completion_tokens",
                usage.getOrDefault("original_usage.candidates_token_count", 0));

        // Whole-prompt tier check: Google's prompt_token_count already includes the cached portion,
        // so totalPromptTokens == inputTokens + cachedReadInputTokens (i.e. the raw prompt_token_count).
        int totalPromptTokens = inputTokens + cachedReadInputTokens;

        return modelPrice.effectiveInputPrice(totalPromptTokens).multiply(BigDecimal.valueOf(inputTokens))
                .add(modelPrice.effectiveOutputPrice(totalPromptTokens).multiply(BigDecimal.valueOf(outputTokens)))
                .add(modelPrice.effectiveCacheReadInputTokenPrice(totalPromptTokens)
                        .multiply(BigDecimal.valueOf(cachedReadInputTokens)));
    }

    /**
     * Calculates the cost of text generation where cached tokens are treated separately from input/output tokens.
     * In this case, cached tokens (both read and creation) are not included in the input or output token counts,
     * but instead are billed at their respective cache-specific rates.
     *
     * @param modelPrice The pricing model for the tokens
     * @param usage Map containing token usage counts
     * @param inputTokensKey Key for input tokens in usage map
     * @param outputTokensKey Key for output tokens in usage map
     * @param cacheReadInputTokensKey Key for cache read tokens in usage map
     * @param cacheCreationInputTokensKey Key for cache creation tokens in usage map
     * @return The calculated cost as a BigDecimal
     */
    private static BigDecimal textGenerationWithCachedTokensNotIncludedInCost(@NonNull ModelPrice modelPrice,
            @NonNull Map<String, Integer> usage,
            String inputTokensKey, String outputTokensKey, String cacheReadInputTokensKey,
            String cacheCreationInputTokensKey) {

        int inputTokens = usage.getOrDefault(inputTokensKey, usage.getOrDefault("prompt_tokens", 0));
        int outputTokens = usage.getOrDefault(outputTokensKey, usage.getOrDefault("completion_tokens", 0));
        int cacheCreationInputTokens = usage.getOrDefault(cacheCreationInputTokensKey,
                usage.getOrDefault(CACHE_CREATION_INPUT_TOKENS_KEY, 0));
        int cacheReadInputTokens = usage.getOrDefault(cacheReadInputTokensKey,
                usage.getOrDefault(CACHE_READ_INPUT_TOKENS_KEY, 0));

        // Whole-prompt tier check: in the Anthropic/Bedrock shape the inputTokensKey value EXCLUDES
        // cached tokens, so the full prompt size for tier classification is input + both cache buckets.
        int totalPromptTokens = inputTokens + cacheCreationInputTokens + cacheReadInputTokens;

        return modelPrice.effectiveInputPrice(totalPromptTokens).multiply(BigDecimal.valueOf(inputTokens))
                .add(modelPrice.effectiveOutputPrice(totalPromptTokens).multiply(BigDecimal.valueOf(outputTokens)))
                .add(modelPrice.effectiveCacheCreationInputTokenPrice(totalPromptTokens)
                        .multiply(BigDecimal.valueOf(cacheCreationInputTokens)))
                .add(modelPrice.effectiveCacheReadInputTokenPrice(totalPromptTokens)
                        .multiply(BigDecimal.valueOf(cacheReadInputTokens)));
    }

    public static BigDecimal defaultCost(@NonNull ModelPrice modelPrice, @NonNull Map<String, Integer> usage) {
        return BigDecimal.ZERO;
    }

    public static BigDecimal audioSpeechCost(@NonNull ModelPrice modelPrice,
            @NonNull Map<String, Integer> usage) {
        int inputCharacters = usage.getOrDefault(ORIGINAL_INPUT_CHARACTERS_KEY,
                usage.getOrDefault(INPUT_CHARACTERS_KEY, 0));
        BigDecimal characterPrice = modelPrice.audioInputCharacterPrice();
        if (inputCharacters <= 0 || !isPositive(characterPrice)) {
            return BigDecimal.ZERO;
        }
        return characterPrice.multiply(BigDecimal.valueOf(inputCharacters));
    }

    public static BigDecimal videoGenerationCost(@NonNull ModelPrice modelPrice,
            @NonNull Map<String, Integer> usage) {
        int durationSeconds = usage.getOrDefault(VIDEO_DURATION_KEY, 0);
        BigDecimal videoPrice = modelPrice.videoOutputPrice();
        if (durationSeconds <= 0 || !isPositive(videoPrice)) {
            return BigDecimal.ZERO;
        }
        return videoPrice.multiply(BigDecimal.valueOf(durationSeconds));
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
