package com.comet.opik.domain.cost;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class CostService {
    private static final Map<String, ModelPrice> modelPrices = new HashMap<>();

    static {
        // OpenAI models
        modelPrices.put("chatgpt-4o-latest", new ModelPrice(new BigDecimal("0.000005"),
                new BigDecimal("0.000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4o", new ModelPrice(new BigDecimal("0.0000025"), new BigDecimal("0.000010"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4o-2024-05-13", new ModelPrice(new BigDecimal("0.000005"),
                new BigDecimal("0.000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4o-2024-08-06", new ModelPrice(new BigDecimal("0.0000025"),
                new BigDecimal("0.000010"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4o-2024-11-20", new ModelPrice(new BigDecimal("0.0000025"),
                new BigDecimal("0.000010"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4o-mini", new ModelPrice(new BigDecimal("0.00000015"), new BigDecimal("0.00000060"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4o-mini-2024-07-18", new ModelPrice(new BigDecimal("0.00000015"),
                new BigDecimal("0.00000060"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-3.5-turbo", new ModelPrice(new BigDecimal("0.0000015"), new BigDecimal("0.000002"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-3.5-turbo-1106", new ModelPrice(new BigDecimal("0.000001"),
                new BigDecimal("0.000002"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-3.5-turbo-0125", new ModelPrice(new BigDecimal("0.00000050"),
                new BigDecimal("0.0000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4", new ModelPrice(new BigDecimal("0.000030"), new BigDecimal("0.000060"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4-0613", new ModelPrice(new BigDecimal("0.000030"), new BigDecimal("0.000060"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4-turbo", new ModelPrice(new BigDecimal("0.000010"), new BigDecimal("0.000030"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4-turbo-2024-04-09", new ModelPrice(new BigDecimal("0.000010"),
                new BigDecimal("0.000030"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4-turbo-preview", new ModelPrice(new BigDecimal("0.000010"),
                new BigDecimal("0.000030"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4-0125-preview", new ModelPrice(new BigDecimal("0.000010"),
                new BigDecimal("0.000030"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gpt-4-1106-preview", new ModelPrice(new BigDecimal("0.000010"),
                new BigDecimal("0.000030"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("o1", new ModelPrice(new BigDecimal("0.000015"), new BigDecimal("0.00006"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("o1-2024-12-17", new ModelPrice(new BigDecimal("0.000015"),
                new BigDecimal("0.000060"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("o1-mini", new ModelPrice(new BigDecimal("0.000003"), new BigDecimal("0.000012"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("o1-mini-2024-09-12", new ModelPrice(new BigDecimal("0.000003"),
                new BigDecimal("0.000012"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("o1-preview", new ModelPrice(new BigDecimal("0.000015"), new BigDecimal("0.000060"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put("o1-preview-2024-09-12", new ModelPrice(new BigDecimal("0.000015"),
                new BigDecimal("0.000060"), SpanCostCalculator::textGenerationCost));

        // VertexAI models
        modelPrices.put("gemini-pro", new ModelPrice(new BigDecimal("0.0000005"),
                new BigDecimal("0.0000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.0-pro", new ModelPrice(new BigDecimal("0.0000005"),
                new BigDecimal("0.0000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.0-pro-001", new ModelPrice(new BigDecimal("0.0000005"),
                new BigDecimal("0.0000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.0-ultra", new ModelPrice(new BigDecimal("0.0000005"),
                new BigDecimal("0.0000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.0-ultra-001", new ModelPrice(new BigDecimal("0.0000005"),
                new BigDecimal("0.0000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.0-pro-002", new ModelPrice(new BigDecimal("0.0000005"),
                new BigDecimal("0.0000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-pro", new ModelPrice(new BigDecimal("0.00000125"),
                new BigDecimal("0.000005"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-pro-002", new ModelPrice(new BigDecimal("0.00000125"),
                new BigDecimal("0.000005"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-pro-001", new ModelPrice(new BigDecimal("0.00000125"),
                new BigDecimal("0.000005"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-pro-preview-0514", new ModelPrice(new BigDecimal("0.000000078125"),
                new BigDecimal("0.0000003125"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-pro-preview-0215", new ModelPrice(new BigDecimal("0.000000078125"),
                new BigDecimal("0.0000003125"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-pro-preview-0409", new ModelPrice(new BigDecimal("0.000000078125"),
                new BigDecimal("0.0000003125"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-flash", new ModelPrice(new BigDecimal("0.000000075"),
                new BigDecimal("0.0000003"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-flash-exp-0827", new ModelPrice(new BigDecimal("0.000000004688"),
                new BigDecimal("0.0000000046875"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-flash-002", new ModelPrice(new BigDecimal("0.000000075"),
                new BigDecimal("0.0000003"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-flash-001", new ModelPrice(new BigDecimal("0.000000075"),
                new BigDecimal("0.0000003"), SpanCostCalculator::textGenerationCost));
        modelPrices.put("gemini-1.5-flash-preview-0514", new ModelPrice(new BigDecimal("0.000000075"),
                new BigDecimal("0.0000000046875"), SpanCostCalculator::textGenerationCost));
    }

    private static final ModelPrice DEFAULT_COST = new ModelPrice(new BigDecimal("0"),
            new BigDecimal("0"), SpanCostCalculator::defaultCost);

    public static BigDecimal calculateCost(@Nullable String rawModelName, @Nullable Map<String, Integer> usage) {
        ModelPrice modelPrice = Optional.ofNullable(rawModelName)
                .map(modelPrices::get)
                .orElse(DEFAULT_COST);

        return modelPrice.calculator().apply(modelPrice, Optional.ofNullable(usage).orElse(Map.of()));
    }
}
