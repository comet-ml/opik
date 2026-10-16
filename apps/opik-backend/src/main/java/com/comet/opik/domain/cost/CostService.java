package com.comet.opik.domain.cost;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.domain.token_usage.SpanUsageFactory;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

@Slf4j
public class CostService {
    private static final char MODEL_PROVIDER_SEPARATOR = '/';
    private static final Map<String, ModelPrice> modelProviderPrices;
    // Usage priced although it contradicts its provider's format: shapes no format reads yet, or numbers that disagree.
    private static final LongCounter USAGE_WARNINGS = GlobalOpenTelemetry.get().getMeter("opik.cost")
            .counterBuilder("opik.cost.usage.warnings")
            .setDescription("Span usage priced despite not matching its provider's usage format (tagged by type)")
            .build();
    private static final AttributeKey<String> WARNING_TYPE_KEY = AttributeKey.stringKey("type");
    public static final String MODEL_PRICES_FILE = "model_prices_and_context_window.json";
    public static final String MODEL_PRICES_OVERRIDES_FILE = "model_prices_overrides.json";
    private static final String BEDROCK_PROVIDER = "bedrock";
    // The price file's sample_spec entry documents its fields instead of pricing a model.
    private static final String SAMPLE_SPEC_ENTRY = "sample_spec";
    // Both hyphen-separated (2025-12-17) and compact (20251217) date suffixes. Anthropic ships
    // compact dates on every dated model id (claude-haiku-4-5-20251001), so without the optional
    // separators a compact-dated name only ever prices when it is present verbatim in the price
    // table -- any name needing normalization (dot form, an `alias_of` entry, or a release the
    // daily LiteLLM sync has not picked up yet) silently fell through to a zero cost.
    private static final String DATE_SUFFIX_PATTERN = "-\\d{4}-?(0[1-9]|1[0-2])-?(0[1-9]|[12]\\d|3[01])$";
    private static final String VERSION_SUFFIX_PATTERN = ":\\d+$";

    static {
        try {
            modelProviderPrices = Collections.unmodifiableMap(parseModelPrices());
        } catch (IOException e) {
            log.error("Failed to load model prices", e);
            throw new UncheckedIOException(e);
        }
    }

    private static final ModelPrice DEFAULT_COST = ModelPrice.empty();

    /** Prices the usage as its provider's format reads it; the usage itself isn't changed. */
    public static BigDecimal calculateCost(@Nullable String modelName, @Nullable String provider,
            @Nullable Map<String, Integer> usage, @Nullable JsonNode metadata) {
        BigDecimal estimatedCost = estimateCost(modelName, provider, usage);
        return estimatedCost.compareTo(BigDecimal.ZERO) > 0 ? estimatedCost : getCostFromMetadata(metadata);
    }

    // Pricing runs while spans are ingested, so it never fails the caller: an error while pricing gives no estimate.
    private static BigDecimal estimateCost(@Nullable String modelName, @Nullable String provider,
            @Nullable Map<String, Integer> usage) {
        try {
            ModelPrice modelPrice = findModelPrice(modelName, provider);
            SpanUsage spanUsage = SpanUsageFactory.create(ProviderRegistry.resolve(provider, modelName).orElse(null),
                    usage);
            spanUsage.warnings().forEach(warning -> USAGE_WARNINGS.add(1,
                    Attributes.of(WARNING_TYPE_KEY, warning.type().name())));
            return modelPrice.calculator().apply(modelPrice, spanUsage);
        } catch (RuntimeException exception) {
            log.warn("Failed to estimate the cost of model '{}', provider '{}'", modelName, provider, exception);
            return BigDecimal.ZERO;
        }
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
     * Fixes issue #5130: Handles Bedrock model names with a version-pin suffix like
     * "anthropic.claude-opus-4-6-v1:0" by stripping the ":N" pin and falling back to the
     * base model name (e.g. "anthropic.claude-opus-4-6-v1") used in the pricing database.
     *
     * The provider is resolved through {@link ProviderRegistry}, and the providers LiteLLM files the same vendor's
     * other models under are searched after it.
     *
     * @param modelName The model name (may contain dots or provider prefix, e.g., "openai/gpt-4o")
     * @param provider The provider name (e.g., "anthropic")
     * @return ModelPrice for the model, or DEFAULT_COST if not found
     */
    private static ModelPrice findModelPrice(String modelName, String provider) {
        if (StringUtils.isBlank(modelName)) {
            return DEFAULT_COST;
        }
        var resolvedProvider = ProviderRegistry.resolve(provider, modelName);
        if (resolvedProvider.isEmpty()) {
            return DEFAULT_COST;
        }
        var lookupOrder = ProviderRegistry.priceLookupOrder(resolvedProvider.get());

        // Preserve the original name so the provider-prefix fallback below can inspect it after
        // all primary lookups have missed under the caller-supplied provider.
        String originalModelName = modelName;

        // Strip provider prefix if present (e.g. "openai/gpt-4o" -> "gpt-4o").
        // LiteLLM sends model names with provider prefix via gen_ai.request.model.
        // This is safe because parseModelPrices() also calls parseModelName() when building
        // the price map, so stored keys never contain a provider prefix. All subsequent
        // normalization steps (dot→hyphen, date suffix stripping) are therefore applied to
        // the same prefix-free name that was used as the key when the map was populated.
        modelName = parseModelName(modelName);

        for (String candidateProvider : lookupOrder) {
            ModelPrice price = findModelPriceUnderProvider(modelName, candidateProvider);
            if (price != null) {
                return price;
            }
        }

        // Provider-prefix fallback: when every primary lookup misses and the model name carries a
        // provider prefix (e.g. "perplexity/sonar"), retry the lookup under that prefix's provider.
        // A prefix that names no provider in the price file finds nothing. This covers models
        // that a caller routes through an aggregator (LlmProviderFactoryImpl enumerates
        // "perplexity/*", "xai/*", "deepseek/*" under OpenRouter, so BudgetGuard invokes
        // calculateCost with provider="openrouter" — the pricing row itself lives under the
        // model's actual origin provider, e.g. litellm_provider: "perplexity"). Only kicks in as
        // a fallback, so no existing lookup semantics change for callers that already pass a
        // matching provider directly.
        int prefixSlash = originalModelName.indexOf('/');
        if (prefixSlash > 0) {
            String providerFromPrefix = ProviderRegistry.toLiteLlmName(originalModelName.substring(0, prefixSlash));
            if (!lookupOrder.contains(providerFromPrefix)) {
                for (String candidateProvider : ProviderRegistry.priceLookupOrder(providerFromPrefix)) {
                    ModelPrice prefixMatch = findModelPriceUnderProvider(modelName, candidateProvider);
                    if (prefixMatch != null) {
                        log.debug(
                                "Found model price using model-name provider prefix. Original model: '{}', supplied provider: '{}', prefix-derived provider: '{}'",
                                originalModelName, provider, candidateProvider);
                        return prefixMatch;
                    }
                }
            }
        }

        log.debug("No model price found for model: '{}' with provider: '{}'", modelName, provider);
        return DEFAULT_COST;
    }

    /**
     * Tries the exact model name under one provider, then its normalized variations. Returns null if none is priced.
     */
    private static ModelPrice findModelPriceUnderProvider(String modelName, String provider) {
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

        // Try stripping version-pin suffix from original name with dots preserved
        // (e.g., "anthropic.claude-opus-4-6-v1:0" -> "anthropic.claude-opus-4-6-v1")
        String baseOriginalVersionName = stripVersionSuffix(modelName);
        if (!baseOriginalVersionName.equalsIgnoreCase(modelName)) {
            String normalizedKey = createModelProviderKey(baseOriginalVersionName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug(
                        "Found model price using original base name after stripping version suffix. Original: '{}', Base: '{}'",
                        modelName, baseOriginalVersionName);
                return normalizedMatch;
            }
        }

        // Try stripping version-pin suffix from normalized name
        // (e.g., "anthropic-claude-opus-4-6-v1:0" -> "anthropic-claude-opus-4-6-v1")
        String baseNormalizedVersionName = stripVersionSuffix(normalizedModelName);
        if (!baseNormalizedVersionName.equalsIgnoreCase(normalizedModelName)) {
            String normalizedKey = createModelProviderKey(baseNormalizedVersionName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug(
                        "Found model price using normalized base name after stripping version suffix. Original: '{}', Base: '{}'",
                        modelName, baseNormalizedVersionName);
                return normalizedMatch;
            }
        }

        return null;
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
     * Date patterns recognized at the end of the model name: YYYY-MM-DD (e.g., "2025-12-17")
     * and the compact YYYYMMDD form (e.g., "20251217") that Anthropic uses for every dated
     * model id. The separators are optional independently, so partially-separated forms are
     * tolerated too; month and day are still range-checked, so a plain 8-digit build number
     * such as "-99999999" is left alone.
     *
     * @param modelName The model name
     * @return Lowercase model name with date suffix removed if present, otherwise lowercase original name
     */
    private static String stripDateSuffix(String modelName) {
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(DATE_SUFFIX_PATTERN, "");
    }

    /**
     * Strips version-pin suffixes from model names to enable fallback pricing lookup.
     * Bedrock sends versioned names (e.g., "anthropic.claude-opus-4-6-v1:0") while the
     * pricing database stores the base name (e.g., "anthropic.claude-opus-4-6-v1").
     *
     * Version pattern recognized: ":N" (one or more digits) at the end of the model name.
     *
     * @param modelName The model name
     * @return Lowercase model name with version suffix removed if present, otherwise lowercase original name
     */
    private static String stripVersionSuffix(String modelName) {
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(VERSION_SUFFIX_PATTERN, "");
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
        Map<String, ModelCostData> modelCosts = new LinkedHashMap<>(JsonUtils.readJsonFile(MODEL_PRICES_FILE,
                new TypeReference<Map<String, ModelCostData>>() {
                }));
        if (modelCosts.isEmpty()) {
            throw new UncheckedIOException(new IOException("Failed to load model prices"));
        }
        modelCosts.remove(SAMPLE_SPEC_ENTRY);

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
     * Loads {@link #MODEL_PRICES_OVERRIDES_FILE} if present. Missing or malformed overrides files
     * are tolerated with a log message.
     */
    private static Map<String, ModelCostData> readOverrides() {
        try {
            Map<String, ModelCostData> overrides = JsonUtils.readJsonFile(MODEL_PRICES_OVERRIDES_FILE,
                    new TypeReference<>() {
                    });
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
