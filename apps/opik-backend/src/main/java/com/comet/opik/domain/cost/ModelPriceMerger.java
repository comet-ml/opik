package com.comet.opik.domain.cost;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;

@UtilityClass
class ModelPriceMerger {

    /**
     * The base price with each rate the row sets replaced. That rate is also zeroed in every prompt tier, as a zero tier
     * rate falls back to the base rate: the row's price then applies at every prompt length. Rates the row has no field
     * for (audio, reasoning, one-hour cache writes) keep their base ratio to the rate they belong to, the way
     * {@link SpanCostCalculator#scaledToTier} keeps them in step with a tier.
     */
    ModelPrice merge(@NonNull ModelPrice base, @NonNull CustomModelPrice row) {
        var merged = base.toBuilder();
        List<ModelPrice.PromptTier> tiers = base.promptTiers();
        if (row.inputPerMillion() != null) {
            BigDecimal input = perToken(row.inputPerMillion());
            merged.inputPrice(input)
                    .inputAudioTokenPrice(
                            SpanCostCalculator.scaledToTier(base.inputAudioTokenPrice(), base.inputPrice(), input));
            tiers = withEachTier(tiers, tier -> tier.toBuilder().inputPrice(BigDecimal.ZERO).build());
        }
        if (row.outputPerMillion() != null) {
            BigDecimal output = perToken(row.outputPerMillion());
            merged.outputPrice(output)
                    .outputAudioTokenPrice(
                            SpanCostCalculator.scaledToTier(base.outputAudioTokenPrice(), base.outputPrice(), output))
                    .outputReasoningTokenPrice(SpanCostCalculator.scaledToTier(base.outputReasoningTokenPrice(),
                            base.outputPrice(), output));
            tiers = withEachTier(tiers, tier -> tier.toBuilder().outputPrice(BigDecimal.ZERO).build());
        }
        if (row.cacheReadPerMillion() != null) {
            merged.cacheReadInputTokenPrice(perToken(row.cacheReadPerMillion()));
            tiers = withEachTier(tiers, tier -> tier.toBuilder().cacheReadInputTokenPrice(BigDecimal.ZERO).build());
        }
        if (row.cacheWritePerMillion() != null) {
            BigDecimal write = perToken(row.cacheWritePerMillion());
            merged.cacheCreationInputTokenPrice(write)
                    .cacheCreationInputTokenPriceAbove1hr(
                            SpanCostCalculator.scaledToTier(base.cacheCreationInputTokenPriceAbove1hr(),
                                    base.cacheCreationInputTokenPrice(), write));
            tiers = withEachTier(tiers, tier -> tier.toBuilder()
                    .cacheCreationInputTokenPrice(BigDecimal.ZERO)
                    .cacheCreationInputTokenPriceAbove1hr(BigDecimal.ZERO)
                    .build());
        }
        ModelPrice price = merged.promptTiers(tiers).build();
        return base.calculator() == SpanCostCalculator.DEFAULT_COST_CALCULATOR && hasTokenPrices(price)
                ? price.toBuilder().calculator(SpanCostCalculator::textGenerationCost).build()
                : price;
    }

    private BigDecimal perToken(BigDecimal perMillion) {
        return perMillion.movePointLeft(6);
    }

    private List<ModelPrice.PromptTier> withEachTier(List<ModelPrice.PromptTier> tiers,
            UnaryOperator<ModelPrice.PromptTier> change) {
        return tiers.isEmpty() ? tiers : tiers.stream().map(change).toList();
    }

    private boolean hasTokenPrices(ModelPrice price) {
        return isPositive(price.inputPrice()) || isPositive(price.outputPrice())
                || isPositive(price.cacheCreationInputTokenPrice()) || isPositive(price.cacheReadInputTokenPrice());
    }

    private boolean isPositive(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0;
    }
}
