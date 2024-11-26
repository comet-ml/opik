package com.comet.opik.domain.cost;

import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.util.Map;

@UtilityClass
class SpanCostCalculator {
    public static BigDecimal textGenerationCost(ModelPrice modelPrice, Map<String, Integer> usage) {
        return modelPrice.getInputPrice().multiply(BigDecimal.valueOf(usage.getOrDefault("prompt_tokens", 0)))
                .add(modelPrice.getOutputPrice()
                        .multiply(BigDecimal.valueOf(usage.getOrDefault("completion_tokens", 0))));
    }

    public static BigDecimal defaultCost(ModelPrice modelPrice, Map<String, Integer> usage) {
        return BigDecimal.ZERO;
    }
}
