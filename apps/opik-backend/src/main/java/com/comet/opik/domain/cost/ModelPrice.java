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
        @NonNull BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator) {

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
}
