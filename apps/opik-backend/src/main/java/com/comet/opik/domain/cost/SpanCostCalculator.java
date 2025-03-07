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

    public static BigDecimal textGenerationWithCacheCostAnthropic(ModelPrice modelPrice, Map<String, Integer> usage) {
        return textGenerationWithCacheCost(modelPrice, usage, "original_usage.input_tokens",
                "original_usage.output_tokens", "original_usage.cache_read_input_tokens",
                "original_usage.cache_creation_input_tokens");
    }

    private static BigDecimal textGenerationWithCacheCost(ModelPrice modelPrice, Map<String, Integer> usage,
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
