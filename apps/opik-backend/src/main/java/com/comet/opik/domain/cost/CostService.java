package com.comet.opik.domain.cost;

import com.comet.opik.domain.llmproviders.OpenaiModelName;
import lombok.NonNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.domain.llmproviders.OpenaiModelName.CHATGPT_4O_LATEST;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O_2024_05_13;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O_2024_08_06;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O_2024_11_20;

public class CostService {
    private static final Map<OpenaiModelName, ModelPrice> modelPrices = Map.of(
            CHATGPT_4O_LATEST, new ModelPrice(new BigDecimal("0.000005"), new BigDecimal("0.000015"),
                    SpanCostCalculator::textGenerationCost),
            GPT_4O, new ModelPrice(new BigDecimal("0.0000025"), new BigDecimal("0.000010"),
                    SpanCostCalculator::textGenerationCost),
            GPT_4O_2024_05_13, new ModelPrice(new BigDecimal("0.000005"), new BigDecimal("0.000015"),
                    SpanCostCalculator::textGenerationCost),
            GPT_4O_2024_08_06, new ModelPrice(new BigDecimal("0.0000025"), new BigDecimal("0.000010"),
                    SpanCostCalculator::textGenerationCost),
            GPT_4O_2024_11_20, new ModelPrice(new BigDecimal("0.0000025"), new BigDecimal("0.000010"),
                    SpanCostCalculator::textGenerationCost));

    private static final ModelPrice DEFAULT_COST = new ModelPrice(new BigDecimal("0"),
            new BigDecimal("0"), SpanCostCalculator::defaultCost);

    public static BigDecimal calculateCost(@NonNull OpenaiModelName modelName, @NonNull Map<String, Integer> usage) {
        var modelPriceNew = Optional.ofNullable(modelPrices.get(modelName))
                .orElse(DEFAULT_COST);
        return modelPriceNew.calculator().apply(modelPriceNew, usage);
    }
}
