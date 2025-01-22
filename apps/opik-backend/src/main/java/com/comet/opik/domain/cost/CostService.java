package com.comet.opik.domain.cost;

import com.comet.opik.domain.llmproviders.OpenaiModelName;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.domain.llmproviders.OpenaiModelName.CHATGPT_4O_LATEST;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_3_5_TURBO;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_3_5_TURBO_0125;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_3_5_TURBO_1106;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O_2024_05_13;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O_2024_08_06;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O_2024_11_20;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O_MINI;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4O_MINI_2024_07_18;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4_0125_PREVIEW;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4_0613;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4_1106_PREVIEW;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4_TURBO;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4_TURBO_2024_04_09;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_4_TURBO_PREVIEW;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_O1;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_O1_2024_12_17;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_O1_MINI;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_O1_MINI_2024_09_12;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_O1_PREVIEW;
import static com.comet.opik.domain.llmproviders.OpenaiModelName.GPT_O1_PREVIEW_2024_09_12;

@Slf4j
public class CostService {
    private static final String WARNING_MISSING_PRICE = "missing price for model '{}'";
    private static final Map<OpenaiModelName, ModelPrice> modelPrices = new EnumMap<>(OpenaiModelName.class);

    static {
        modelPrices.put(CHATGPT_4O_LATEST, new ModelPrice(new BigDecimal("0.000005"),
                new BigDecimal("0.000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4O, new ModelPrice(new BigDecimal("0.0000025"), new BigDecimal("0.000010"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4O_2024_05_13, new ModelPrice(new BigDecimal("0.000005"),
                new BigDecimal("0.000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4O_2024_08_06, new ModelPrice(new BigDecimal("0.0000025"),
                new BigDecimal("0.000010"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4O_2024_11_20, new ModelPrice(new BigDecimal("0.0000025"),
                new BigDecimal("0.000010"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4O_MINI, new ModelPrice(new BigDecimal("0.00000015"), new BigDecimal("0.00000060"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4O_MINI_2024_07_18, new ModelPrice(new BigDecimal("0.00000015"),
                new BigDecimal("0.00000060"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_3_5_TURBO, new ModelPrice(new BigDecimal("0.0000015"), new BigDecimal("0.000002"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_3_5_TURBO_1106, new ModelPrice(new BigDecimal("0.000001"),
                new BigDecimal("0.000002"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_3_5_TURBO_0125, new ModelPrice(new BigDecimal("0.00000050"),
                new BigDecimal("0.0000015"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4, new ModelPrice(new BigDecimal("0.000030"), new BigDecimal("0.000060"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4_0613, new ModelPrice(new BigDecimal("0.000030"), new BigDecimal("0.000060"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4_TURBO, new ModelPrice(new BigDecimal("0.000010"), new BigDecimal("0.000030"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4_TURBO_2024_04_09, new ModelPrice(new BigDecimal("0.000010"),
                new BigDecimal("0.000030"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4_TURBO_PREVIEW, new ModelPrice(new BigDecimal("0.000010"),
                new BigDecimal("0.000030"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4_0125_PREVIEW, new ModelPrice(new BigDecimal("0.000010"),
                new BigDecimal("0.000030"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_4_1106_PREVIEW, new ModelPrice(new BigDecimal("0.000010"),
                new BigDecimal("0.000030"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_O1, new ModelPrice(new BigDecimal("0.000015"), new BigDecimal("0.00006"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_O1_2024_12_17, new ModelPrice(new BigDecimal("0.000015"),
                new BigDecimal("0.000060"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_O1_MINI, new ModelPrice(new BigDecimal("0.000003"), new BigDecimal("0.000012"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_O1_MINI_2024_09_12, new ModelPrice(new BigDecimal("0.000003"),
                new BigDecimal("0.000012"), SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_O1_PREVIEW, new ModelPrice(new BigDecimal("0.000015"), new BigDecimal("0.000060"),
                SpanCostCalculator::textGenerationCost));
        modelPrices.put(GPT_O1_PREVIEW_2024_09_12, new ModelPrice(new BigDecimal("0.000015"),
                new BigDecimal("0.000060"), SpanCostCalculator::textGenerationCost));
    }

    private static final ModelPrice DEFAULT_COST = new ModelPrice(new BigDecimal("0"),
            new BigDecimal("0"), SpanCostCalculator::defaultCost);

    public static BigDecimal calculateCost(@Nullable String rawModelName, @Nullable Map<String, Integer> usage) {
        return OpenaiModelName.byValue(rawModelName)
                .map(modelName -> calculateCost(modelName, usage))
                .orElseGet(() -> calculateDefaultCost(usage));
    }

    private static BigDecimal calculateCost(OpenaiModelName modelName, Map<String, Integer> usage) {
        var modelPrice = Optional.ofNullable(modelPrices.get(modelName))
                .orElseGet(() -> {
                    log.warn(WARNING_MISSING_PRICE, modelName);
                    return DEFAULT_COST;
                });
        return calculateCost(modelPrice, usage);
    }

    private static BigDecimal calculateDefaultCost(Map<String, Integer> usage) {
        return calculateCost(DEFAULT_COST, usage);
    }

    private static BigDecimal calculateCost(ModelPrice modelPrice, Map<String, Integer> usage) {
        return modelPrice.calculator().apply(modelPrice, Optional.ofNullable(usage).orElse(Map.of()));
    }
}
