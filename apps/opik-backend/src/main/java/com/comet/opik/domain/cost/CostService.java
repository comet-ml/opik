package com.comet.opik.domain.cost;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
public class CostService {
    private static final Map<String, ModelPrice> modelPrices = new HashMap<>();
    private static final Set<String> providers = Set.of("openai", "vertex_ai-language-models");
    private static final String PRICES_FILE = "model_prices_and_context_window.json";

    static {
        try {
            parseModelPrices();
        } catch (IOException e) {
            log.error("Failed to load model prices", e);
            throw new UncheckedIOException(e);
        }
    }

    private static final ModelPrice DEFAULT_COST = new ModelPrice(new BigDecimal("0"),
            new BigDecimal("0"), SpanCostCalculator::defaultCost);

    public static BigDecimal calculateCost(@Nullable String rawModelName, @Nullable Map<String, Integer> usage) {
        ModelPrice modelPrice = Optional.ofNullable(rawModelName)
                .map(modelPrices::get)
                .orElse(DEFAULT_COST);

        return modelPrice.calculator().apply(modelPrice, Optional.ofNullable(usage).orElse(Map.of()));
    }

    private static void parseModelPrices() throws IOException {
        Map<String, ModelCostData> modelCosts = JsonUtils.readJsonFile(PRICES_FILE, new TypeReference<>() {
        });
        if (modelCosts.isEmpty()) {
            throw new UncheckedIOException(new IOException("Failed to load model prices"));
        }

        modelCosts.forEach((modelName, modelCost) -> {
            String provider = Optional.ofNullable(modelCost.litellmProvider()).orElse("");
            if (providers.contains(provider)) {
                BigDecimal inputPrice = Optional.ofNullable(modelCost.inputCostPerToken()).map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                BigDecimal outputPrice = Optional.ofNullable(modelCost.outputCostPerToken()).map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                if (inputPrice.compareTo(BigDecimal.ZERO) > 0 || outputPrice.compareTo(BigDecimal.ZERO) > 0) {
                    modelPrices.put(modelName,
                            new ModelPrice(inputPrice, outputPrice, SpanCostCalculator::textGenerationCost));
                }
            }
        });
    }
}
