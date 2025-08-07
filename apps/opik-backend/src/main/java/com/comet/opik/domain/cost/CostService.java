package com.comet.opik.domain.cost;

import com.comet.opik.api.ModelCostData;
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
            "bedrock_converse", "bedrock");
    private static final String PRICES_FILE = "model_prices_and_context_window.json";
    private static final String BEDROCK_PROVIDER = "bedrock";
    private static final Map<String, BiFunction<ModelPrice, Map<String, Integer>, BigDecimal>> PROVIDERS_CACHE_COST_CALCULATOR = Map
            .of("anthropic", SpanCostCalculator::textGenerationWithCacheCostAnthropic,
                    "openai", SpanCostCalculator::textGenerationWithCacheCostOpenAI,
                    "bedrock", SpanCostCalculator::textGenerationWithCacheCostBedrock,
                    "bedrock_converse", SpanCostCalculator::textGenerationWithCacheCostBedrock);

    private static final Map<String, BiFunction<ModelPrice, Map<String, Integer>, BigDecimal>> PROVIDERS_MULTIMODAL_CACHE_COST_CALCULATOR = Map
            .of("anthropic", SpanCostCalculator::multimodalCostWithCacheAnthropic,
                    "openai", SpanCostCalculator::multimodalCostWithCache);

    static {
        try {
            modelProviderPrices = Collections.unmodifiableMap(parseModelPrices());
        } catch (IOException e) {
            log.error("Failed to load model prices", e);
            throw new UncheckedIOException(e);
        }
    }

    private static final ModelPrice DEFAULT_COST = new ModelPrice(new BigDecimal("0"),
            new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"),
            new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"),
            SpanCostCalculator::defaultCost);

    public static BigDecimal calculateCost(@Nullable String modelName, @Nullable String provider,
            @Nullable Map<String, Integer> usage, @Nullable JsonNode metadata) {
        ModelPrice modelPrice = Optional.ofNullable(modelName)
                .flatMap(mn -> Optional.ofNullable(provider).map(p -> createModelProviderKey(mn, p)))
                .map(modelProviderPrices::get)
                .orElse(DEFAULT_COST);

        BigDecimal estimatedCost = modelPrice.calculator().apply(modelPrice,
                Optional.ofNullable(usage).orElse(Map.of()));

        return estimatedCost.compareTo(BigDecimal.ZERO) > 0 ? estimatedCost : getCostFromMetadata(metadata);
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
        Map<String, ModelCostData> modelCosts = JsonUtils.readJsonFile(PRICES_FILE, new TypeReference<>() {
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

                // Parse multimodal pricing fields
                BigDecimal audioInputPrice = Optional.ofNullable(modelCost.inputCostPerAudioToken())
                        .map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                BigDecimal audioOutputPrice = Optional.ofNullable(modelCost.outputCostPerAudioToken())
                        .map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                BigDecimal imageInputPrice = Optional.ofNullable(modelCost.inputCostPerImage()).map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                BigDecimal videoInputPrice = BigDecimal.ZERO;
                BigDecimal audioInputPerSecondPrice = Optional.ofNullable(modelCost.inputCostPerAudioPerSecond())
                        .map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                BigDecimal videoInputPerSecondPrice = Optional.ofNullable(modelCost.inputCostPerVideoPerSecond())
                        .map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);

                // Determine appropriate calculator based on pricing structure
                BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator = SpanCostCalculator::defaultCost;
                boolean hasMultimodalPricing = audioInputPrice.compareTo(BigDecimal.ZERO) > 0 ||
                        audioOutputPrice.compareTo(BigDecimal.ZERO) > 0 ||
                        imageInputPrice.compareTo(BigDecimal.ZERO) > 0 ||
                        audioInputPerSecondPrice.compareTo(BigDecimal.ZERO) > 0 ||
                        videoInputPerSecondPrice.compareTo(BigDecimal.ZERO) > 0;

                if (hasMultimodalPricing) {
                    if (cacheCreationInputTokenPrice.compareTo(BigDecimal.ZERO) > 0
                            || cacheReadInputTokenPrice.compareTo(BigDecimal.ZERO) > 0) {
                        // Only use cache-aware multimodal calculator for known providers
                        if (PROVIDERS_MULTIMODAL_CACHE_COST_CALCULATOR.containsKey(provider)) {
                            calculator = PROVIDERS_MULTIMODAL_CACHE_COST_CALCULATOR.get(provider);
                        } else {
                            // Fallback to regular multimodal cost (without cache) for unknown providers
                            calculator = SpanCostCalculator::multimodalCost;
                        }
                    } else {
                        calculator = SpanCostCalculator::multimodalCost;
                    }
                } else
                    if (cacheCreationInputTokenPrice.compareTo(BigDecimal.ZERO) > 0
                            || cacheReadInputTokenPrice.compareTo(BigDecimal.ZERO) > 0) {
                                // Only use cache-aware calculator for known providers
                                if (PROVIDERS_CACHE_COST_CALCULATOR.containsKey(provider)) {
                                    calculator = PROVIDERS_CACHE_COST_CALCULATOR.get(provider);
                                } else {
                                    // Fallback to regular text generation cost (without cache) for unknown providers
                                    calculator = SpanCostCalculator::textGenerationCost;
                                }
                            } else
                        if (inputPrice.compareTo(BigDecimal.ZERO) > 0 || outputPrice.compareTo(BigDecimal.ZERO) > 0) {
                            calculator = SpanCostCalculator::textGenerationCost;
                        }

                parsedModelPrices.put(
                        createModelProviderKey(parseModelName(modelName), PROVIDERS_MAPPING.get(provider)),
                        new ModelPrice(inputPrice, outputPrice, cacheCreationInputTokenPrice,
                                cacheReadInputTokenPrice, audioInputPrice, audioOutputPrice, imageInputPrice,
                                videoInputPrice, audioInputPerSecondPrice, videoInputPerSecondPrice, calculator));
            }
        });

        return parsedModelPrices;
    }

    private static String parseModelName(String modelName) {
        int prefixIndex = modelName.indexOf('/');
        return prefixIndex == -1 ? modelName : modelName.substring(prefixIndex + 1);
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
