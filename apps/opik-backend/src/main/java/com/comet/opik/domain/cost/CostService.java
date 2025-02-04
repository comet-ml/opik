package com.comet.opik.domain.cost;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

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
        parseModelPrices();
    }

    private static final ModelPrice DEFAULT_COST = new ModelPrice(new BigDecimal("0"),
            new BigDecimal("0"), SpanCostCalculator::defaultCost);

    public static BigDecimal calculateCost(@Nullable String rawModelName, @Nullable Map<String, Integer> usage) {
        ModelPrice modelPrice = Optional.ofNullable(rawModelName)
                .map(modelPrices::get)
                .orElse(DEFAULT_COST);

        return modelPrice.calculator().apply(modelPrice, Optional.ofNullable(usage).orElse(Map.of()));
    }

    private static void parseModelPrices() {
        JsonNode pricesJson = JsonUtils.readJsonFile(PRICES_FILE);
        pricesJson.fields().forEachRemaining(modelEntry -> {
            String modelName = modelEntry.getKey();
            JsonNode modelData = modelEntry.getValue();
            String provider = Optional.ofNullable(modelData.get("litellm_provider"))
                    .map(JsonNode::asText).orElse("");
            if (!modelName.startsWith("ft:") && providers.contains(provider)) {
                BigDecimal inputPrice = getPrice("input_cost_per_token", modelData);
                BigDecimal outputPrice = getPrice("output_cost_per_token", modelData);
                if (inputPrice.compareTo(BigDecimal.ZERO) > 0 || outputPrice.compareTo(BigDecimal.ZERO) > 0) {
                    modelPrices.put(modelName,
                            new ModelPrice(inputPrice, outputPrice, SpanCostCalculator::textGenerationCost));
                }
            }
        });
    }

    private static BigDecimal getPrice(String key, JsonNode data) {
        return Optional.ofNullable(data.get(key))
                .map(JsonNode::asText)
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
    }
}
