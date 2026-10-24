package com.comet.opik.domain.cost;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The model prices loaded from LiteLLM's price file, the OpenRouter price file and Opik's overrides, keyed by {@code <model>/<provider>}.
 */
@Slf4j
public class ModelPriceTable {
    private static final char MODEL_PROVIDER_SEPARATOR = '/';
    private static final Map<String, ModelPrice> modelProviderPrices;
    private static final Set<String> pricedProviders;
    public static final String MODEL_PRICES_FILE = "model_prices_and_context_window.json";
    public static final String MODEL_PRICES_OVERRIDES_FILE = "model_prices_overrides.json";
    /**
     * OpenRouter's models in the format of {@link #MODEL_PRICES_FILE}, generated daily from OpenRouter's model list by
     * {@code scripts/update_openrouter_model_prices.py}, as LiteLLM's price file no longer lists them.
     */
    public static final String MODEL_PRICES_OPENROUTER_FILE = "model_prices_openrouter.json";
    /** The rows of a price file, keyed by model name. */
    public static final TypeReference<Map<String, ModelCostData>> MODEL_COSTS_TYPE_REFERENCE = new TypeReference<>() {
    };
    private static final String BEDROCK_PROVIDER = "bedrock";
    // The price file's sample_spec entry documents its fields instead of pricing a model.
    private static final String SAMPLE_SPEC_ENTRY = "sample_spec";

    static {
        modelProviderPrices = Collections.unmodifiableMap(parseModelPrices());
        pricedProviders = modelProviderPrices.keySet().stream()
                .map(key -> key.substring(key.lastIndexOf(MODEL_PROVIDER_SEPARATOR) + 1))
                .collect(Collectors.toUnmodifiableSet());
    }

    static ModelPrice get(String modelName, String provider) {
        return modelProviderPrices.get(createModelProviderKey(modelName, provider));
    }

    static boolean isPricedProvider(@NonNull String provider) {
        return pricedProviders.contains(provider);
    }

    private static Map<String, ModelPrice> parseModelPrices() {
        Map<String, ModelCostData> modelCosts = readModelCosts();

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
        Map<String, ModelCostData> overrides = readOverrides();
        applyOverrides(parsedModelPrices, modelCosts, overrides);
        return parsedModelPrices;
    }

    /**
     * The rows of {@link #MODEL_PRICES_FILE} and {@link #MODEL_PRICES_OPENROUTER_FILE}, keyed by model name, without
     * the {@code sample_spec} entry. Opik's overrides are not applied.
     */
    public static Map<String, ModelCostData> readModelCosts() {
        Map<String, ModelCostData> modelCosts = new LinkedHashMap<>(readPriceFile(MODEL_PRICES_FILE));
        modelCosts.remove(SAMPLE_SPEC_ENTRY);
        modelCosts.putAll(readPriceFile(MODEL_PRICES_OPENROUTER_FILE));
        return modelCosts;
    }

    /** Both files are bundled, so one that can't be read or holds no model is a broken build, not a state to run in. */
    private static Map<String, ModelCostData> readPriceFile(String file) {
        try {
            var modelCosts = JsonUtils.readJsonFile(file, MODEL_COSTS_TYPE_REFERENCE);
            if (modelCosts.isEmpty()) {
                throw new IllegalStateException("No entries found in model prices file '%s'".formatted(file));
            }
            return modelCosts;
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read model prices file '%s'".formatted(file), exception);
        }
    }

    /**
     * Loads {@link #MODEL_PRICES_OVERRIDES_FILE} if present. Missing or malformed overrides files
     * are tolerated with a log message.
     */
    private static Map<String, ModelCostData> readOverrides() {
        try {
            Map<String, ModelCostData> overrides = JsonUtils.readJsonFile(MODEL_PRICES_OVERRIDES_FILE,
                    MODEL_COSTS_TYPE_REFERENCE);
            return overrides == null ? Map.of() : overrides;
        } catch (IOException | NullPointerException e) {
            log.warn("No model price overrides loaded ('{}'): '{}'", MODEL_PRICES_OVERRIDES_FILE, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Merges the overrides' entries into the given price map.
     */
    private static void applyOverrides(Map<String, ModelPrice> prices, Map<String, ModelCostData> upstream,
            Map<String, ModelCostData> overrides) {
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
            log.warn("Override entry '{}' has a blank provider or a legacy Bedrock name (provider '{}'); skipping",
                    modelName, override.litellmProvider());
            return;
        }
        ModelPrice price = buildModelPrice(override);
        if (price != null) {
            prices.put(runtimeKey, price);
        }
    }

    /**
     * Computes the runtime key {@code <parsedModel>/<litellmProvider>} for a price-map entry.
     * Returns null if the provider is blank or the entry is invalid for the provider
     * (e.g. legacy Bedrock paths).
     */
    private static String buildRuntimeKey(String modelName, ModelCostData modelCost) {
        String provider = Optional.ofNullable(modelCost.litellmProvider()).orElse("");
        if (provider.isBlank() || !isValidModelProvider(modelName, provider)) {
            return null;
        }
        return createModelProviderKey(parseModelName(modelName), provider);
    }

    private static ModelPrice buildModelPrice(ModelCostData modelCost) {
        String provider = Optional.ofNullable(modelCost.litellmProvider()).orElse("");
        if (provider.isBlank()) {
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
        BigDecimal outputAudioTokenPrice = Optional.ofNullable(modelCost.outputCostPerAudioToken())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal outputReasoningTokenPrice = Optional.ofNullable(modelCost.outputCostPerReasoningToken())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        // Whole-prompt tiers: LiteLLM's above_{128k,200k,272k}_tokens rates replace the base
        // rate wholesale once the prompt strictly exceeds the threshold. Only include a tier
        // when at least one of its rates is non-zero; that keeps the tier list empty for the
        // ~99% of models with no tier fields configured. Sorted DESCENDING so the effective
        // helpers on ModelPrice pick the highest applicable tier without extra bookkeeping.
        // Reachable models today: OpenRouter's Qwen3 and ByteDance Seed rows at 128K, Gemini 2.5 Pro /
        // Claude Sonnet 4.5 at 200K, GPT-5.4 and GPT-5.5 (openai and azure) at 272K.
        List<ModelPrice.PromptTier> promptTiers = new ArrayList<>();
        addTierIfPresent(promptTiers, ModelPrice.TIER_THRESHOLD_272K,
                modelCost.inputCostPerTokenAbove272kTokens(), modelCost.outputCostPerTokenAbove272kTokens(),
                null, null);
        addTierIfPresent(promptTiers, ModelPrice.TIER_THRESHOLD_200K,
                modelCost.inputCostPerTokenAbove200kTokens(), modelCost.outputCostPerTokenAbove200kTokens(),
                modelCost.cacheCreationInputTokenCostAbove200kTokens(),
                modelCost.cacheReadInputTokenCostAbove200kTokens());
        addTierIfPresent(promptTiers, ModelPrice.TIER_THRESHOLD_128K,
                modelCost.inputCostPerTokenAbove128kTokens(), modelCost.outputCostPerTokenAbove128kTokens(),
                null, null);

        ModelMode mode = ModelMode.fromValue(modelCost.mode());

        BiFunction<ModelPrice, SpanUsage, BigDecimal> calculator = resolveCalculator(mode, inputPrice, outputPrice,
                cacheCreationInputTokenPrice, cacheReadInputTokenPrice, videoOutputPrice, audioInputCharacterPrice);

        return ModelPrice.builder()
                .inputPrice(inputPrice)
                .outputPrice(outputPrice)
                .cacheCreationInputTokenPrice(cacheCreationInputTokenPrice)
                .cacheReadInputTokenPrice(cacheReadInputTokenPrice)
                .cacheCreationInputTokenPriceAbove1hr(
                        Optional.ofNullable(modelCost.cacheCreationInputTokenCostAbove1hr())
                                .map(BigDecimal::new)
                                .orElse(BigDecimal.ZERO))
                .videoOutputPrice(videoOutputPrice)
                .audioInputCharacterPrice(audioInputCharacterPrice)
                .inputAudioTokenPrice(inputAudioTokenPrice)
                .outputAudioTokenPrice(outputAudioTokenPrice)
                .outputReasoningTokenPrice(outputReasoningTokenPrice)
                .calculator(calculator)
                .promptTiers(List.copyOf(promptTiers))
                .build();
    }

    /**
     * Append one {@link ModelPrice.PromptTier} to {@code tiers} when at least one of the
     * per-rate JSON strings is non-null and parses to a positive amount. Nulls (rate not
     * published for this tier — e.g. LiteLLM does not carry cache rates at 128K/272K) are
     * treated as ZERO by the tier record and skipped by the effective-price helpers.
     */
    private static void addTierIfPresent(List<ModelPrice.PromptTier> tiers, int threshold,
            String inputPrice, String outputPrice,
            String cacheCreationInputTokenPrice, String cacheReadInputTokenPrice) {
        BigDecimal in = parseTierRate(inputPrice);
        BigDecimal out = parseTierRate(outputPrice);
        BigDecimal cc = parseTierRate(cacheCreationInputTokenPrice);
        BigDecimal cr = parseTierRate(cacheReadInputTokenPrice);
        if (isPositive(in) || isPositive(out) || isPositive(cc) || isPositive(cr)) {
            tiers.add(ModelPrice.PromptTier.builder()
                    .threshold(threshold)
                    .inputPrice(in)
                    .outputPrice(out)
                    .cacheCreationInputTokenPrice(cc)
                    .cacheReadInputTokenPrice(cr)
                    .build());
        }
    }

    private static BigDecimal parseTierRate(String raw) {
        return Optional.ofNullable(raw).map(BigDecimal::new).orElse(BigDecimal.ZERO);
    }

    static String parseModelName(String modelName) {
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

    private static BiFunction<ModelPrice, SpanUsage, BigDecimal> resolveCalculator(
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

        if (isPositive(inputPrice) || isPositive(outputPrice) || isPositive(cacheCreationInputTokenPrice)
                || isPositive(cacheReadInputTokenPrice)) {
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
