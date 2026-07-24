package com.comet.opik.domain.cost;

import lombok.Builder;
import lombok.NonNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

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
        @NonNull List<PromptTier> promptTiers) {

    /**
     * Whole-prompt tier thresholds for the {@code *_above_NNNk_tokens} rates published by
     * LiteLLM. When the total prompt strictly exceeds a threshold the tier's rate replaces the
     * base rate wholesale (matches LiteLLM's {@code _get_token_base_cost}). Reachable models
     * today: Gemini 1.5 Flash at 128K, Gemini 2.5 Pro / Claude Sonnet 4.5 at 200K, GPT-5.4 /
     * GPT-5.5 (openai and azure) at 272K.
     */
    public static final int TIER_THRESHOLD_128K = 128_000;

    public static final int TIER_THRESHOLD_200K = 200_000;

    public static final int TIER_THRESHOLD_272K = 272_000;

    /**
     * One prompt-size tier for a model: the threshold plus each rate the tier overrides. Any
     * rate left at {@link BigDecimal#ZERO} means "this tier does not override that rate — fall
     * through to a lower tier or the base rate." LiteLLM publishes the four rates below at 200K
     * but only {@code input}/{@code output} at 128K and 272K, so cache fields on those tiers
     * are typically zero and correctly no-op via {@link #applicableTier}.
     */
    @Builder(toBuilder = true)
    public record PromptTier(
            int threshold,
            @NonNull BigDecimal inputPrice,
            @NonNull BigDecimal outputPrice,
            @NonNull BigDecimal cacheCreationInputTokenPrice,
            @NonNull BigDecimal cacheReadInputTokenPrice) {
    }

    /**
     * Returns a builder pre-populated with zero rates, the no-op {@code defaultCost} calculator,
     * and an empty tier list. Callers only override the fields they care about, which keeps test
     * fixtures and the empty placeholder concise without re-introducing overloaded constructors.
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
                .promptTiers(List.of());
    }

    public static ModelPrice empty() {
        return defaultBuilder().build();
    }

    public BigDecimal effectiveInputPrice(int totalPromptTokens) {
        return applicableTier(totalPromptTokens, PromptTier::inputPrice).orElse(inputPrice);
    }

    public BigDecimal effectiveOutputPrice(int totalPromptTokens) {
        return applicableTier(totalPromptTokens, PromptTier::outputPrice).orElse(outputPrice);
    }

    public BigDecimal effectiveCacheCreationInputTokenPrice(int totalPromptTokens) {
        return applicableTier(totalPromptTokens, PromptTier::cacheCreationInputTokenPrice)
                .orElse(cacheCreationInputTokenPrice);
    }

    public BigDecimal effectiveCacheReadInputTokenPrice(int totalPromptTokens) {
        return applicableTier(totalPromptTokens, PromptTier::cacheReadInputTokenPrice)
                .orElse(cacheReadInputTokenPrice);
    }

    /**
     * Walk {@code promptTiers} — which callers store sorted DESCENDING by threshold — and return
     * the first tier whose threshold is strictly exceeded by {@code totalPromptTokens} AND whose
     * requested rate is greater than zero. Highest applicable tier wins; zero-valued rates never
     * suppress a lower tier's rate or the base rate.
     */
    private Optional<BigDecimal> applicableTier(int totalPromptTokens, Function<PromptTier, BigDecimal> rate) {
        return promptTiers.stream()
                .filter(tier -> totalPromptTokens > tier.threshold())
                .map(rate)
                .filter(price -> price.compareTo(BigDecimal.ZERO) > 0)
                .findFirst();
    }
}
