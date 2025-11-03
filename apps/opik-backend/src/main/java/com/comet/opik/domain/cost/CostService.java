package com.comet.opik.domain.cost;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.domain.llm.ModelNameMapper;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

@Slf4j
public class CostService {
    private static final char MODEL_PROVIDER_SEPARATOR = '/';
    private static final Map<String, ModelPrice> modelProviderPrices;
    private static final Map<String, String> PROVIDERS_MAPPING = Map.of(
            "openai", "openai",
            "vertex_ai-language-models", "google_vertexai",
            "gemini", "google_ai",
            "anthropic", "anthropic",
            "vertex_ai-anthropic_models", "anthropic_vertexai",
            "bedrock", "bedrock",
            "bedrock_converse", "bedrock",
            "groq", "groq",
            "openrouter", "openrouter",
            "deepinfra", "deepinfra");

    public static final String MODEL_PRICES_FILE = "model_prices_and_context_window.json";
    private static final String BEDROCK_PROVIDER = "bedrock";
    private static final Map<String, BiFunction<ModelPrice, Map<String, Integer>, BigDecimal>> PROVIDERS_CACHE_COST_CALCULATOR = Map
            .of("anthropic", SpanCostCalculator::textGenerationWithCacheCostAnthropic,
                    "openai", SpanCostCalculator::textGenerationWithCacheCostOpenAI,
                    "bedrock", SpanCostCalculator::textGenerationWithCacheCostBedrock,
                    "bedrock_converse", SpanCostCalculator::textGenerationWithCacheCostBedrock);

    static {
        try {
            modelProviderPrices = Collections.unmodifiableMap(parseModelPrices());
        } catch (IOException e) {
            log.error("Failed to load model prices", e);
            throw new UncheckedIOException(e);
        }
    }

    private static final ModelPrice DEFAULT_COST = new ModelPrice(new BigDecimal("0"),
            new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), SpanCostCalculator::defaultCost);

    public static BigDecimal calculateCost(@Nullable String modelName, @Nullable String provider,
            @Nullable Map<String, Integer> usage, @Nullable JsonNode metadata) {
        ModelPrice modelPrice = findModelPrice(modelName, provider);

        BigDecimal estimatedCost = modelPrice.calculator().apply(modelPrice,
                Optional.ofNullable(usage).orElse(Map.of()));

        return estimatedCost.compareTo(BigDecimal.ZERO) > 0 ? estimatedCost : getCostFromMetadata(metadata);
    }

    /**
     * Find model price with sophisticated matching including suffix matching.
     * This handles cases like "qwen/qwen2.5-vl-32b-instruct" matching "deepinfra/Qwen/Qwen2.5-VL-32B-Instruct".
     */
    private static ModelPrice findModelPrice(@Nullable String modelName, @Nullable String provider) {
        if (modelName == null || provider == null) {
            return DEFAULT_COST;
        }

        // Free tier models (:free suffix) have zero cost regardless of canonical mapping
        // This handles cases like "qwen/qwen2.5-vl-32b-instruct:free" which maps to
        // a paid canonical key but should have $0 cost
        if (modelName.toLowerCase().contains(":free")) {
            log.debug("Model '{}' is free tier variant, returning zero cost", modelName);
            return DEFAULT_COST;
        }

        // Resolve to canonical JSON key using explicit enum mappings
        var canonicalModel = ModelNameMapper.resolveCanonicalKey(modelName);
        // Normalize both model and provider for consistent key format
        var normalizedModel = ModelNameMapper.normalize(canonicalModel);
        var normalizedProvider = ModelNameMapper.normalize(provider);

        // Try direct lookup with normalized canonical model + provider
        var key = createModelProviderKey(normalizedModel, normalizedProvider);
        var found = modelProviderPrices.get(key);
        if (found != null) {
            return found;
        }

        log.debug("Model price not found for model='{}' (canonical: '{}'), provider='{}'",
                modelName, canonicalModel, provider);
        return DEFAULT_COST;
    }

    public static BigDecimal getCostFromMetadata(JsonNode metadata) {
        return Optional.ofNullable(metadata)
                .map(md -> md.get("cost"))
                .map(cost -> Optional.ofNullable(cost.get("currency"))
                        .map(JsonNode::asText)
                        .filter("USD"::equals)
                        .map(currency -> cost.get("total_tokens"))
                        .map(JsonNode::decimalValue)
                        .orElse(BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
    }

    private static Map<String, ModelPrice> parseModelPrices() throws IOException {
        Map<String, ModelCostData> modelCosts = JsonUtils.readJsonFile(MODEL_PRICES_FILE, new TypeReference<>() {
        });
        if (modelCosts.isEmpty()) {
            throw new UncheckedIOException(new IOException("Failed to load model prices"));
        }

        Map<String, ModelPrice> parsedModelPrices = new HashMap<>();
        modelCosts.forEach((modelName, modelCost) -> {
            String provider = Optional.ofNullable(modelCost.litellmProvider()).orElse("");
            if (PROVIDERS_MAPPING.containsKey(provider)) {

                if (!isValidModelProvider(modelName, PROVIDERS_MAPPING.get(provider))) {
                    return;
                }

                BigDecimal inputPrice = Optional.ofNullable(modelCost.inputCostPerToken()).map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                BigDecimal outputPrice = Optional.ofNullable(modelCost.outputCostPerToken()).map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                BigDecimal cacheCreationInputTokenPrice = Optional.ofNullable(modelCost.cacheCreationInputTokenCost())
                        .map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                BigDecimal cacheReadInputTokenPrice = Optional.ofNullable(modelCost.cacheReadInputTokenCost())
                        .map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);

                BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator = SpanCostCalculator::defaultCost;
                if (cacheCreationInputTokenPrice.compareTo(BigDecimal.ZERO) > 0
                        || cacheReadInputTokenPrice.compareTo(BigDecimal.ZERO) > 0) {
                    calculator = PROVIDERS_CACHE_COST_CALCULATOR.getOrDefault(provider,
                            SpanCostCalculator::textGenerationCost);
                } else if (inputPrice.compareTo(BigDecimal.ZERO) > 0 || outputPrice.compareTo(BigDecimal.ZERO) > 0) {
                    calculator = SpanCostCalculator::textGenerationCost;
                }

                // Use normalized canonical key from JSON
                String normalizedModelName = ModelNameMapper.normalize(modelName);
                String normalizedProvider = ModelNameMapper.normalize(PROVIDERS_MAPPING.get(provider));
                String compositeKey = createModelProviderKey(normalizedModelName, normalizedProvider);

                parsedModelPrices.put(compositeKey,
                        new ModelPrice(inputPrice, outputPrice, cacheCreationInputTokenPrice,
                                cacheReadInputTokenPrice, calculator));
            }
        });

        return parsedModelPrices;
    }

    private static String createModelProviderKey(String modelName, String provider) {
        return modelName + MODEL_PROVIDER_SEPARATOR + provider;
    }

    private static boolean isValidModelProvider(String modelName, String provider) {
        if (BEDROCK_PROVIDER.equals(provider) && modelName.contains("/")) {
            // Bedrock models with / in the name are not supported as considered old
            return false;
        }

        return true;
    }
}