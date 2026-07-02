package com.comet.opik.domain.cost;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.UsageUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

@Slf4j
public class CostService {
    private static final char MODEL_PROVIDER_SEPARATOR = '/';
    private static final Map<String, ModelPrice> modelProviderPrices;
    private static final Map<String, String> PROVIDERS_MAPPING = Map.ofEntries(
            Map.entry("openai", "openai"),
            Map.entry("vertex_ai-language-models", "google_vertexai"),
            Map.entry("gemini", "google_ai"),
            Map.entry("anthropic", "anthropic"),
            Map.entry("vertex_ai-anthropic_models", "anthropic_vertexai"),
            Map.entry("bedrock", "bedrock"),
            Map.entry("bedrock_converse", "bedrock"),
            Map.entry("groq", "groq"),
            Map.entry("jina_ai", "jina_ai"),
            Map.entry("elastic", "elastic"),
            Map.entry("microsoft", "azure"),
            Map.entry("azure", "azure"),
            Map.entry("mistral", "mistral"));
    public static final String MODEL_PRICES_FILE = "model_prices_and_context_window.json";
    public static final String MODEL_PRICES_OVERRIDES_FILE = "model_prices_overrides.json";
    private static final String BEDROCK_PROVIDER = "bedrock";
    private static final String DATE_SUFFIX_PATTERN = "-\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$";
    private static final Map<String, BiFunction<ModelPrice, Map<String, Integer>, BigDecimal>> PROVIDERS_CACHE_COST_CALCULATOR = Map
            .of("anthropic", SpanCostCalculator::textGenerationWithCacheCostAnthropic,
                    "openai", SpanCostCalculator::textGenerationWithCacheCostOpenAI,
                    "azure", SpanCostCalculator::textGenerationWithCacheCostOpenAI,
                    "bedrock", SpanCostCalculator::textGenerationWithCacheCostBedrock,
                    "bedrock_converse", SpanCostCalculator::textGenerationWithCacheCostBedrock,
                    "vertex_ai-language-models", SpanCostCalculator::textGenerationWithCacheCostGoogle,
                    "gemini", SpanCostCalculator::textGenerationWithCacheCostGoogle,
                    "vertex_ai-anthropic_models", SpanCostCalculator::textGenerationWithCacheCostAnthropic);

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

        // Drop null token counts before pricing: calculators read usage via getOrDefault(key, 0),
        // which returns null (not the default) for a key present with a null value, then NPEs
        // unboxing it in BigDecimal.valueOf(...).
        BigDecimal estimatedCost = modelPrice.calculator().apply(modelPrice, UsageUtils.sanitizeUsage(usage));

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
     * Fixes issue #5621: Handles model names with provider prefix like "openai/gpt-4o"
     * sent by LiteLLM via gen_ai.request.model, by stripping the prefix before lookup.
     *
     * @param modelName The model name (may contain dots or provider prefix, e.g., "openai/gpt-4o")
     * @param provider The provider name (e.g., "anthropic")
     * @return ModelPrice for the model, or DEFAULT_COST if not found
     */
    private static ModelPrice findModelPrice(String modelName, String provider) {
        if (StringUtils.isBlank(modelName) || StringUtils.isBlank(provider)) {
            return DEFAULT_COST;
        }

        // Strip provider prefix if present (e.g. "openai/gpt-4o" -> "gpt-4o").
        // LiteLLM sends model names with provider prefix via gen_ai.request.model.
        // This is safe because parseModelPrices() also calls parseModelName() when building
        // the price map, so stored keys never contain a provider prefix. All subsequent
        // normalization steps (dot→hyphen, date suffix stripping) are therefore applied to
        // the same prefix-free name that was used as the key when the map was populated.
        modelName = parseModelName(modelName);

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

        // Try stripping date suffix from original name with dots preserved (e.g., "gpt-5.2-2025-12-17" -> "gpt-5.2")
        String baseOriginalModelName = stripDateSuffix(modelName);
        if (!baseOriginalModelName.equalsIgnoreCase(modelName)) {
            String normalizedKey = createModelProviderKey(baseOriginalModelName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug(
                        "Found model price using original base name after stripping date suffix. Original: '{}', Base: '{}'",
                        modelName, baseOriginalModelName);
                return normalizedMatch;
            }
        }

        // Try stripping date suffix from normalized name (e.g., "gpt-5-2-2025-12-17" -> "gpt-5-2")
        String baseNormalizedModelName = stripDateSuffix(normalizedModelName);
        if (!baseNormalizedModelName.equalsIgnoreCase(normalizedModelName)) {
            String normalizedKey = createModelProviderKey(baseNormalizedModelName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug(
                        "Found model price using normalized base name after stripping date suffix. Original: '{}', Base: '{}'",
                        modelName, baseNormalizedModelName);
                return normalizedMatch;
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
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(DATE_SUFFIX_PATTERN, "");
    }

    public static BigDecimal getCostFromMetadata(JsonNode metadata) {
        return Optional.ofNullable(metadata)
                .map(md -> md.get("cost"))
                .map(cost -> Optional.ofNullable(cost.get("currency"))
                        .map(JsonNode::asText)
                        .filter("USD"::equals)
                        .flatMap(currency -> Optional.ofNullable(cost.get("total_cost")))
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
            String runtimeKey = buildRuntimeKey(modelName, modelCost);
            if (runtimeKey == null) {
                return;
            }
            ModelPrice price = buildModelPrice(modelCost);
            if (price != null) {
                parsedModelPrices.put(runtimeKey, price);
            }
        });

        // Apply Opik-owned overrides that survive the daily LiteLLM sync workflow.
        // Three flavors are supported in the overrides file: aliases (`alias_of` pointing at an
        // upstream key), brand-new models, and price overrides for existing keys.
        applyOverrides(parsedModelPrices, modelCosts);

        return parsedModelPrices;
    }

    /**
     * Loads {@link #MODEL_PRICES_OVERRIDES_FILE} if present and merges its entries into the
     * given price map. Missing or malformed overrides files are tolerated with a log message.
     */
    private static void applyOverrides(Map<String, ModelPrice> prices,
            Map<String, ModelCostData> upstream) {
        Map<String, ModelCostData> overrides;
        try {
            overrides = JsonUtils.readJsonFile(MODEL_PRICES_OVERRIDES_FILE, new TypeReference<>() {
            });
        } catch (IOException | NullPointerException e) {
            log.warn("No model price overrides loaded ('{}'): '{}'", MODEL_PRICES_OVERRIDES_FILE, e.getMessage());
            return;
        }
        if (overrides == null || overrides.isEmpty()) {
            return;
        }

        // Apply direct overrides first, then aliases. Aliases resolve their target against `upstream`
        // (they exist to reuse an upstream LiteLLM row under a different name), but their *price* is
        // read from the merged `prices` map — so a direct override that re-prices an upstream key
        // must already be in place when its aliases are resolved. Order in the JSON file is irrelevant.
        List<Map.Entry<String, ModelCostData>> aliasEntries = new ArrayList<>();
        overrides.forEach((modelName, override) -> {
            if (StringUtils.isNotBlank(override.aliasOf())) {
                aliasEntries.add(Map.entry(modelName, override));
            } else {
                applyDirectOverride(prices, modelName, override);
            }
        });
        aliasEntries.forEach(entry -> applyAlias(prices, upstream, entry.getKey(), entry.getValue()));
    }

    private static void applyAlias(Map<String, ModelPrice> prices, Map<String, ModelCostData> upstream,
            String aliasName, ModelCostData override) {
        String targetName = override.aliasOf();
        ModelCostData target = upstream.get(targetName);
        if (target == null) {
            log.warn("Override alias '{}' points to unknown upstream model '{}'; skipping", aliasName, targetName);
            return;
        }
        if (StringUtils.isNotBlank(target.aliasOf())) {
            log.warn("Override alias '{}' points to another alias '{}'; alias-of-alias is not supported, skipping",
                    aliasName, targetName);
            return;
        }
        String targetKey = buildRuntimeKey(targetName, target);
        if (targetKey == null) {
            log.warn("Override alias '{}' target '{}' has no loadable provider; skipping", aliasName, targetName);
            return;
        }
        ModelPrice targetPrice = prices.get(targetKey);
        if (targetPrice == null) {
            log.warn("Override alias '{}' target '{}' (key '{}') has no loaded price; skipping",
                    aliasName, targetName, targetKey);
            return;
        }
        // Aliases inherit the target's litellm_provider so the alias and target share a provider in the runtime key.
        String aliasKey = buildRuntimeKey(aliasName, target);
        if (aliasKey == null) {
            return;
        }
        prices.put(aliasKey, targetPrice);
    }

    private static void applyDirectOverride(Map<String, ModelPrice> prices, String modelName, ModelCostData override) {
        String runtimeKey = buildRuntimeKey(modelName, override);
        if (runtimeKey == null) {
            log.warn("Override entry '{}' has unknown provider '{}'; skipping",
                    modelName, override.litellmProvider());
            return;
        }
        ModelPrice price = buildModelPrice(override);
        if (price != null) {
            prices.put(runtimeKey, price);
        }
    }

    /**
     * Computes the runtime key {@code <parsedModel>/<canonicalProvider>} for a price-map entry.
     * Returns null if the provider isn't in {@link #PROVIDERS_MAPPING} or the entry is invalid
     * for the resolved provider (e.g. legacy Bedrock paths).
     */
    private static String buildRuntimeKey(String modelName, ModelCostData modelCost) {
        String provider = Optional.ofNullable(modelCost.litellmProvider()).orElse("");
        String canonical = PROVIDERS_MAPPING.get(provider);
        if (canonical == null) {
            return null;
        }
        if (!isValidModelProvider(modelName, canonical)) {
            return null;
        }
        return createModelProviderKey(parseModelName(modelName), canonical);
    }

    private static ModelPrice buildModelPrice(ModelCostData modelCost) {
        String provider = Optional.ofNullable(modelCost.litellmProvider()).orElse("");
        if (!PROVIDERS_MAPPING.containsKey(provider)) {
            return null;
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
        BigDecimal audioInputCharacterPrice = Optional.ofNullable(modelCost.inputCostPerCharacter())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal inputAudioTokenPrice = Optional.ofNullable(modelCost.inputCostPerAudioToken())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        // Tier rates: above_200k_tokens variants. Models without a tier (most) leave these null
        // in the LiteLLM JSON; we default to zero and the effective-price helpers on ModelPrice
        // fall through to the base rate in that case.
        BigDecimal inputPriceAbove200kTokens = Optional.ofNullable(modelCost.inputCostPerTokenAbove200kTokens())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal outputPriceAbove200kTokens = Optional.ofNullable(modelCost.outputCostPerTokenAbove200kTokens())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal cacheCreationInputTokenPriceAbove200kTokens = Optional
                .ofNullable(modelCost.cacheCreationInputTokenCostAbove200kTokens())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal cacheReadInputTokenPriceAbove200kTokens = Optional
                .ofNullable(modelCost.cacheReadInputTokenCostAbove200kTokens())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        ModelMode mode = ModelMode.fromValue(modelCost.mode());

        BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator = resolveCalculator(provider, mode,
                inputPrice, outputPrice, cacheCreationInputTokenPrice, cacheReadInputTokenPrice,
                videoOutputPrice, audioInputCharacterPrice);

        return ModelPrice.builder()
                .inputPrice(inputPrice)
                .outputPrice(outputPrice)
                .cacheCreationInputTokenPrice(cacheCreationInputTokenPrice)
                .cacheReadInputTokenPrice(cacheReadInputTokenPrice)
                .videoOutputPrice(videoOutputPrice)
                .audioInputCharacterPrice(audioInputCharacterPrice)
                .inputAudioTokenPrice(inputAudioTokenPrice)
                .calculator(calculator)
                .inputPriceAbove200kTokens(inputPriceAbove200kTokens)
                .outputPriceAbove200kTokens(outputPriceAbove200kTokens)
                .cacheCreationInputTokenPriceAbove200kTokens(cacheCreationInputTokenPriceAbove200kTokens)
                .cacheReadInputTokenPriceAbove200kTokens(cacheReadInputTokenPriceAbove200kTokens)
                .build();
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
            BigDecimal videoOutputPrice,
            BigDecimal audioInputCharacterPrice) {

        if (mode.isVideoGeneration() && isPositive(videoOutputPrice)) {
            return SpanCostCalculator::videoGenerationCost;
        }

        if (mode.isAudioSpeech() && isPositive(audioInputCharacterPrice)) {
            return SpanCostCalculator::audioSpeechCost;
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

        boolean isAudioSpeech() {
            return this == AUDIO_SPEECH;
        }
    }
}
