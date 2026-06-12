package com.comet.opik.domain.cost;

import lombok.Builder;
import lombok.NonNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiFunction;

@Builder(toBuilder = true)
public record ModelPrice(
        @NonNull BigDecimal inputPrice,
        @NonNull BigDecimal outputPrice,
        @NonNull BigDecimal cacheCreationInputTokenPrice,
        @NonNull BigDecimal cacheReadInputTokenPrice,
        @NonNull BigDecimal videoOutputPrice,
        @NonNull BigDecimal audioInputCharacterPrice,
        @NonNull BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator,
        @NonNull BigDecimal inputPriceAbove200kTokens,
        @NonNull BigDecimal outputPriceAbove200kTokens,
        @NonNull BigDecimal cacheCreationInputTokenPriceAbove200kTokens,
        @NonNull BigDecimal cacheReadInputTokenPriceAbove200kTokens) {

    /**
     * Whole-prompt tier threshold for the {@code *_above_200k_tokens} rates published by
     * LiteLLM (e.g. Gemini 2.5 Pro, Claude Sonnet 4.5). When the total prompt size strictly
     * exceeds this, every token in the request is billed at the tier rate (this matches
     * LiteLLM's {@code _get_token_base_cost} which replaces the base rate wholesale).
     */
    public static final int TIER_THRESHOLD_200K = 200_000;

    /**
     * Backwards-compatible constructor: callers (and many existing unit tests) construct a
     * ModelPrice without tier rates. Defaults the four tier-rate fields to zero so the
     * effective-price helpers below fall through to the base rates.
     */
    public ModelPrice(BigDecimal inputPrice, BigDecimal outputPrice,
            BigDecimal cacheCreationInputTokenPrice, BigDecimal cacheReadInputTokenPrice,
            BigDecimal videoOutputPrice, BigDecimal audioInputCharacterPrice,
            BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator) {
        this(inputPrice, outputPrice, cacheCreationInputTokenPrice, cacheReadInputTokenPrice,
                videoOutputPrice, audioInputCharacterPrice, calculator,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static ModelPrice empty() {
        return new ModelPrice(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SpanCostCalculator::defaultCost);
    }

    public BigDecimal effectiveInputPrice(int totalPromptTokens) {
        return useTier(totalPromptTokens, inputPriceAbove200kTokens) ? inputPriceAbove200kTokens : inputPrice;
    }

    public BigDecimal effectiveOutputPrice(int totalPromptTokens) {
        return useTier(totalPromptTokens, outputPriceAbove200kTokens) ? outputPriceAbove200kTokens : outputPrice;
    }

    public BigDecimal effectiveCacheCreationInputTokenPrice(int totalPromptTokens) {
        return useTier(totalPromptTokens, cacheCreationInputTokenPriceAbove200kTokens)
                ? cacheCreationInputTokenPriceAbove200kTokens
                : cacheCreationInputTokenPrice;
    }

    public BigDecimal effectiveCacheReadInputTokenPrice(int totalPromptTokens) {
        return useTier(totalPromptTokens, cacheReadInputTokenPriceAbove200kTokens)
                ? cacheReadInputTokenPriceAbove200kTokens
                : cacheReadInputTokenPrice;
    }

    private static boolean useTier(int totalPromptTokens, BigDecimal tierRate) {
        return totalPromptTokens > TIER_THRESHOLD_200K && tierRate.compareTo(BigDecimal.ZERO) > 0;
    }
}
