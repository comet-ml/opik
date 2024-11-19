package com.comet.opik.domain.cost;

import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
class SpanCostCalculator {
    public static double textGenerationCost(ModelPrice modelPrice, Map<String, Integer> usage) {
        return (modelPrice.getInputPricePer1M() * usage.getOrDefault("prompt_tokens", 0)
                + modelPrice.getOutputPricePer1M() * usage.getOrDefault("completion_tokens", 0)) / 1000000;
    }

    public static double defaultCost(ModelPrice modelPrice, Map<String, Integer> usage) {
        return -1;
    }
}
