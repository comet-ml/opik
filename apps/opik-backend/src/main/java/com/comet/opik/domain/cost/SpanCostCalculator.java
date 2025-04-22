package com.comet.opik.domain.cost;

import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.util.Map;

@UtilityClass
class SpanCostCalculator {
    public static BigDecimal textGenerationCost(ModelPrice modelPrice, Map<String, Integer> usage) {
        return modelPrice.inputPrice().multiply(BigDecimal.valueOf(usage.getOrDefault("prompt_tokens", 0)))
                .add(modelPrice.outputPrice()
                        .multiply(BigDecimal.valueOf(usage.getOrDefault("completion_tokens", 0))));
    }

    public static BigDecimal textGenerationWithCacheCostOpenAI(ModelPrice modelPrice, Map<String, Integer> usage) {

        // In OpenAI usage format, input tokens includes the cached input tokens, so we need to substract them to compute the correct input token count
        // Don't generalize yet as other providers seems to separate the cached tokens from non-cached tokens

        // Get the input tokens (SDK version below 1.6.0 logged prompt_tokens, while 1.6.0+ logged original_usage.prompt_tokens)
        int inputTokens = usage.getOrDefault("original_usage.prompt_tokens", usage.getOrDefault("prompt_tokens", 0));

        // Get the cached read input tokens
        int cachedReadInputTokens = usage.getOrDefault("original_usage.prompt_tokens_details.cached_tokens", 0);

        // If we got cached tokens, substract them from the input tokens count
        if (cachedReadInputTokens > 0) {
            inputTokens = Math.max(0, inputTokens - cachedReadInputTokens);
        }

        // Get the output tokens (SDK version below 1.6.0 logged completion_tokens, while 1.6.0+ logged original_usage.completion_tokens)
        int outputTokens = usage.getOrDefault("original_usage.completion_tokens",
                usage.getOrDefault("completion_tokens", 0));

        return modelPrice.inputPrice().multiply(BigDecimal.valueOf(inputTokens))
                .add(modelPrice.outputPrice().multiply(BigDecimal.valueOf(outputTokens)))
                .add(modelPrice.cacheReadInputTokenPrice().multiply(BigDecimal.valueOf(cachedReadInputTokens)));
    }

    public static BigDecimal textGenerationWithCacheCostAnthropic(ModelPrice modelPrice, Map<String, Integer> usage) {
        return textGenerationWithCachedTokensNotIncludedInCost(modelPrice, usage, "original_usage.input_tokens",
                "original_usage.output_tokens", "original_usage.cache_read_input_tokens",
                "original_usage.cache_creation_input_tokens");
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
    private static BigDecimal textGenerationWithCachedTokensNotIncludedInCost(ModelPrice modelPrice,
            Map<String, Integer> usage,
            String inputTokensKey, String outputTokensKey, String cacheReadInputTokensKey,
            String cacheCreationInputTokensKey) {

        return modelPrice.inputPrice().multiply(BigDecimal.valueOf(usage.getOrDefault(inputTokensKey, 0)))
                .add(modelPrice.outputPrice()
                        .multiply(BigDecimal.valueOf(usage.getOrDefault(outputTokensKey, 0))))
                .add(modelPrice.cacheCreationInputTokenPrice()
                        .multiply(BigDecimal.valueOf(usage.getOrDefault(cacheCreationInputTokensKey, 0))))
                .add(modelPrice.cacheReadInputTokenPrice()
                        .multiply(BigDecimal.valueOf(usage.getOrDefault(cacheReadInputTokensKey, 0))));
    }

    public static BigDecimal defaultCost(ModelPrice modelPrice, Map<String, Integer> usage) {
        return BigDecimal.ZERO;
    }
}
