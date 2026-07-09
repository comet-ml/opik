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
        @NonNull BigDecimal inputAudioTokenPrice,
        @NonNull BigDecimal outputAudioTokenPrice,
        @NonNull BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator,
        @NonNull BigDecimal inputPriceAbove128kTokens,
        @NonNull BigDecimal outputPriceAbove128kTokens,
        @NonNull BigDecimal inputPriceAbove200kTokens,
        @NonNull BigDecimal outputPriceAbove200kTokens,
        @NonNull BigDecimal cacheCreationInputTokenPriceAbove200kTokens,
        @NonNull BigDecimal cacheReadInputTokenPriceAbove200kTokens,
        @NonNull BigDecimal inputPriceAbove272kTokens,
        @NonNull BigDecimal outputPriceAbove272kTokens) {

    /**
     * Whole-prompt tier thresholds for the {@code *_above_NNNk_tokens} rates published by
     * LiteLLM: when the total prompt size strictly exceeds one, every token in the request
     * bills at that tier's rate (matches LiteLLM's {@code _get_token_base_cost}, which
     * replaces the base rate wholesale). Models today publish at most one applicable
     * threshold (e.g. Gemini 1.5 Flash at 128k, Gemini 2.5 Pro at 200k, GPT-5.4/5.5 at 272k),
     * but the effective-price helpers below check descending so that if a model ever
     * publishes multiple tiers, the highest applicable one wins.
     */
    public static final int TIER_THRESHOLD_128K = 128_000;

    public static final int TIER_THRESHOLD_200K = 200_000;

    public static final int TIER_THRESHOLD_272K = 272_000;

    /**
     * Returns a builder pre-populated with zero rates and the no-op {@code defaultCost} calculator.
     * Callers only override the fields they care about, which keeps test fixtures and the empty
     * placeholder concise without re-introducing overloaded constructors.
     */
    public static ModelPriceBuilder defaultBuilder() {
        return builder()
                .inputPrice(BigDecimal.ZERO)
                .outputPrice(BigDecimal.ZERO)
                .cacheCreationInputTokenPrice(BigDecimal.ZERO)
                .cacheReadInputTokenPrice(BigDecimal.ZERO)
                .videoOutputPrice(BigDecimal.ZERO)
                .audioInputCharacterPrice(BigDecimal.ZERO)
                .inputAudioTokenPrice(BigDecimal.ZERO)
                .outputAudioTokenPrice(BigDecimal.ZERO)
                .calculator(SpanCostCalculator::defaultCost)
                .inputPriceAbove128kTokens(BigDecimal.ZERO)
                .outputPriceAbove128kTokens(BigDecimal.ZERO)
                .inputPriceAbove200kTokens(BigDecimal.ZERO)
                .outputPriceAbove200kTokens(BigDecimal.ZERO)
                .cacheCreationInputTokenPriceAbove200kTokens(BigDecimal.ZERO)
                .cacheReadInputTokenPriceAbove200kTokens(BigDecimal.ZERO)
                .inputPriceAbove272kTokens(BigDecimal.ZERO)
                .outputPriceAbove272kTokens(BigDecimal.ZERO);
    }

    public static ModelPrice empty() {
        return defaultBuilder().build();
    }

    public BigDecimal effectiveInputPrice(int totalPromptTokens) {
        return effectiveTieredPrice(totalPromptTokens, inputPrice,
                inputPriceAbove128kTokens, inputPriceAbove200kTokens, inputPriceAbove272kTokens);
    }

    public BigDecimal effectiveOutputPrice(int totalPromptTokens) {
        return effectiveTieredPrice(totalPromptTokens, outputPrice,
                outputPriceAbove128kTokens, outputPriceAbove200kTokens, outputPriceAbove272kTokens);
    }

    public BigDecimal effectiveCacheCreationInputTokenPrice(int totalPromptTokens) {
        return useTier(totalPromptTokens, TIER_THRESHOLD_200K, cacheCreationInputTokenPriceAbove200kTokens)
                ? cacheCreationInputTokenPriceAbove200kTokens
                : cacheCreationInputTokenPrice;
    }

    public BigDecimal effectiveCacheReadInputTokenPrice(int totalPromptTokens) {
        return useTier(totalPromptTokens, TIER_THRESHOLD_200K, cacheReadInputTokenPriceAbove200kTokens)
                ? cacheReadInputTokenPriceAbove200kTokens
                : cacheReadInputTokenPrice;
    }

    /**
     * Shared descending-threshold traversal for input/output tiered rates so both effective-price
     * accessors stay in lock-step. Highest applicable tier wins (272K, then 200K, then 128K); if
     * none apply — either because the prompt is below every threshold or because the tier rate is
     * zero (unpublished) — the base rate is returned.
     */
    private static BigDecimal effectiveTieredPrice(int totalPromptTokens, BigDecimal baseRate,
            BigDecimal above128kRate, BigDecimal above200kRate, BigDecimal above272kRate) {
        if (useTier(totalPromptTokens, TIER_THRESHOLD_272K, above272kRate)) {
            return above272kRate;
        }
        if (useTier(totalPromptTokens, TIER_THRESHOLD_200K, above200kRate)) {
            return above200kRate;
        }
        if (useTier(totalPromptTokens, TIER_THRESHOLD_128K, above128kRate)) {
            return above128kRate;
        }
        return baseRate;
    }

    private static boolean useTier(int totalPromptTokens, int threshold, BigDecimal tierRate) {
        return totalPromptTokens > threshold && tierRate.compareTo(BigDecimal.ZERO) > 0;
    }
}
