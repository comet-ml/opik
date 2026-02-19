package com.comet.opik.domain.cost;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
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
            "groq", "groq");
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

    private static final ModelPrice DEFAULT_COST = ModelPrice.empty();

    public static BigDecimal calculateCost(@Nullable String modelName, @Nullable String provider,
            @Nullable Map<String, Integer> usage, @Nullable JsonNode metadata) {
        ModelPrice modelPrice = findModelPrice(modelName, provider);

        BigDecimal estimatedCost = modelPrice.calculator().apply(modelPrice,
                Optional.ofNullable(usage).orElse(Map.of()));

        return estimatedCost.compareTo(BigDecimal.ZERO) > 0 ? estimatedCost : getCostFromMetadata(metadata);
    }

    /**
     * Finds model pricing information with fallback to normalized model names.
     * This method provides backwards compatibility by first trying exact match,
     * then falling back to normalized variations.
     *
     * Fixes issue #4114: Handles model name variations like "claude-3.5-sonnet"
     * by normalizing to "claude-3-5-sonnet" format used in pricing database.
     *
     * Fixes issue #5018: Handles model names with date suffixes like "gpt-5.2-2025-12-17"
     * by stripping the date suffix and falling back to the base model name.
     *
     * @param modelName The model name (may contain dots, e.g., "claude-3.5-sonnet")
     * @param provider The provider name (e.g., "anthropic")
     * @return ModelPrice for the model, or DEFAULT_COST if not found
     */
    private static ModelPrice findModelPrice(String modelName, String provider) {
        if (StringUtils.isBlank(modelName) || StringUtils.isBlank(provider)) {
            return DEFAULT_COST;
        }

        // Try exact match first (backwards compatibility)
        String exactKey = createModelProviderKey(modelName, provider);
        ModelPrice exactMatch = modelProviderPrices.get(exactKey);
        if (exactMatch != null) {
            return exactMatch;
        }

        // Try normalized model name (replace dots with hyphens and lowercase)
        String normalizedModelName = normalizeModelName(modelName);
        if (!normalizedModelName.equalsIgnoreCase(modelName)) {
            String normalizedKey = createModelProviderKey(normalizedModelName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug("Found model price using normalized name. Original: '{}', Normalized: '{}'",
                        modelName, normalizedModelName);
                return normalizedMatch;
            }
        }

        // Try stripping date suffix from normalized name (e.g., "gpt-5-2-2025-12-17" -> "gpt-5-2")
        String baseNormalizedModelName = stripDateSuffix(normalizedModelName);
        if (!baseNormalizedModelName.equalsIgnoreCase(normalizedModelName)) {
            String baseNormalizedKey = createModelProviderKey(baseNormalizedModelName, provider);
            ModelPrice baseNormalizedMatch = modelProviderPrices.get(baseNormalizedKey);
            if (baseNormalizedMatch != null) {
                log.debug(
                        "Found model price using normalized base name after stripping date suffix. Original: '{}', Base: '{}'",
                        modelName, baseNormalizedModelName);
                return baseNormalizedMatch;
            }
        }

        // Try stripping date suffix from original name with dots preserved (e.g., "gpt-5.2-2025-12-17" -> "gpt-5.2")
        String baseOriginalModelName = stripDateSuffix(modelName);
        if (!baseOriginalModelName.equalsIgnoreCase(modelName)) {
            String baseOriginalKey = createModelProviderKey(baseOriginalModelName, provider);
            ModelPrice baseOriginalMatch = modelProviderPrices.get(baseOriginalKey);
            if (baseOriginalMatch != null) {
                log.debug(
                        "Found model price using original base name after stripping date suffix. Original: '{}', Base: '{}'",
                        modelName, baseOriginalModelName);
                return baseOriginalMatch;
            }
        }

        log.debug("No model price found for model: '{}' with provider: '{}'", modelName, provider);
        return DEFAULT_COST;
    }

    /**
     * Normalizes model names by replacing dots with hyphens and converting to lowercase.
     * This handles common naming variations where users specify model names
     * like "claude-3.5-sonnet" or "Claude-3.5-Sonnet" but the pricing database
     * uses "claude-3-5-sonnet".
     *
     * @param modelName The original model name (caller guarantees non-null and non-blank)
     * @return Normalized model name with dots replaced by hyphens and lowercase
     */
    private static String normalizeModelName(String modelName) {
        return modelName.replace('.', '-').toLowerCase(Locale.ROOT);
    }

    /**
     * Strips date suffixes from model names to enable fallback pricing lookup.
     * This handles cases where providers return dated model names (e.g., "gpt-5.2-2025-12-17")
     * but the pricing database only has the base model name (e.g., "gpt-5.2").
     *
     * Date patterns recognized: YYYY-MM-DD (e.g., "2025-12-17") at the end of the model name.
     *
     * @param modelName The model name
     * @return Lowercase model name with date suffix removed if present, otherwise lowercase original name
     */
    private static String stripDateSuffix(String modelName) {
        // Pattern: ends with -YYYY-MM-DD where YYYY is 2000-2099, MM is 01-12, DD is 01-31
        // This is a simple heuristic that should work for most date-suffixed model names
        String datePattern = "-\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$";
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(datePattern, "");
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
                BigDecimal videoOutputPrice = Optional.ofNullable(modelCost.outputCostPerVideoPerSecond())
                        .map(BigDecimal::new)
                        .orElse(BigDecimal.ZERO);
                ModelMode mode = ModelMode.fromValue(modelCost.mode());

                BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator = resolveCalculator(provider, mode,
                        inputPrice, outputPrice, cacheCreationInputTokenPrice, cacheReadInputTokenPrice,
                        videoOutputPrice);

                parsedModelPrices.put(
                        createModelProviderKey(parseModelName(modelName), PROVIDERS_MAPPING.get(provider)),
                        new ModelPrice(inputPrice, outputPrice, cacheCreationInputTokenPrice,
                                cacheReadInputTokenPrice, videoOutputPrice, calculator));
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

    private static BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> resolveCalculator(
            String provider,
            ModelMode mode,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            BigDecimal cacheCreationInputTokenPrice,
            BigDecimal cacheReadInputTokenPrice,
            BigDecimal videoOutputPrice) {

        if (mode.isVideoGeneration() && isPositive(videoOutputPrice)) {
            return SpanCostCalculator::videoGenerationCost;
        }

        if (isPositive(cacheCreationInputTokenPrice) || isPositive(cacheReadInputTokenPrice)) {
            return PROVIDERS_CACHE_COST_CALCULATOR.getOrDefault(provider, SpanCostCalculator::textGenerationCost);
        }

        if (isPositive(inputPrice) || isPositive(outputPrice)) {
            return SpanCostCalculator::textGenerationCost;
        }

        return SpanCostCalculator::defaultCost;
    }

    private static boolean isPositive(BigDecimal value) {
        return Optional.ofNullable(value).map(v -> v.compareTo(BigDecimal.ZERO) > 0).orElse(false);
    }

    @RequiredArgsConstructor
    private enum ModelMode {
        TEXT_GENERATION("text_generation"),
        CHAT("chat"),
        EMBEDDING("embedding"),
        COMPLETION("completion"),
        IMAGE_GENERATION("image_generation"),
        AUDIO_TRANSCRIPTION("audio_transcription"),
        AUDIO_SPEECH("audio_speech"),
        MODERATION("moderation"),
        RERANK("rerank"),
        SEARCH("search"),
        VIDEO_GENERATION("video_generation");

        private static final ModelMode DEFAULT = TEXT_GENERATION;
        private final String value;

        static ModelMode fromValue(String rawValue) {
            if (StringUtils.isBlank(rawValue)) {
                return DEFAULT;
            }

            for (ModelMode mode : values()) {
                if (mode.value.equalsIgnoreCase(rawValue)) {
                    return mode;
                }
            }

            return DEFAULT;
        }

        boolean isVideoGeneration() {
            return this == VIDEO_GENERATION;
        }
    }
}
