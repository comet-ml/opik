package com.comet.opik.domain.cost;

import com.comet.opik.domain.token_usage.SpanUsageFactory;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class CostService {
    // Usage priced although it contradicts its provider's format: shapes no format reads yet, or numbers that disagree.
    private static final LongCounter USAGE_WARNINGS = GlobalOpenTelemetry.get().getMeter("opik.cost")
            .counterBuilder("opik.cost.usage.warnings")
            .setDescription("Span usage priced despite not matching its provider's usage format (tagged by type)")
            .build();
    private static final AttributeKey<String> WARNING_TYPE_KEY = AttributeKey.stringKey("type");
    // Both hyphen-separated (2025-12-17) and compact (20251217) date suffixes. Anthropic ships
    // compact dates on every dated model id (claude-haiku-4-5-20251001), so without the optional
    // separators a compact-dated name only ever prices when it is present verbatim in the price
    // table -- any name needing normalization (dot form, an `alias_of` entry, or a release the
    // daily LiteLLM sync has not picked up yet) silently fell through to a zero cost.
    private static final String DATE_SUFFIX_PATTERN = "-\\d{4}-?(0[1-9]|1[0-2])-?(0[1-9]|[12]\\d|3[01])$";
    private static final String VERSION_SUFFIX_PATTERN = ":\\d+$";

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
        // This is safe because ModelPriceTable also calls parseModelName() when building
        // the price map, so stored keys never contain a provider prefix. All subsequent
        // normalization steps (dot→hyphen, date suffix stripping) are therefore applied to
        // the same prefix-free name that was used as the key when the map was populated.
        modelName = ModelPriceTable.parseModelName(modelName);

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
        ModelPrice exactMatch = ModelPriceTable.get(modelName, provider);
        if (exactMatch != null) {
            return exactMatch;
        }

        // Try normalized model name (replace dots with hyphens and lowercase)
        String normalizedModelName = normalizeModelName(modelName);
        if (!normalizedModelName.equalsIgnoreCase(modelName)) {
            ModelPrice normalizedMatch = ModelPriceTable.get(normalizedModelName, provider);
            if (normalizedMatch != null) {
                log.debug("Found model price using normalized name. Original: '{}', Normalized: '{}'",
                        modelName, normalizedModelName);
                return normalizedMatch;
            }
        }

        // Try stripping date suffix from original name with dots preserved (e.g., "gpt-5.2-2025-12-17" -> "gpt-5.2")
        String baseOriginalModelName = stripDateSuffix(modelName);
        if (!baseOriginalModelName.equalsIgnoreCase(modelName)) {
            ModelPrice normalizedMatch = ModelPriceTable.get(baseOriginalModelName, provider);
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
            ModelPrice normalizedMatch = ModelPriceTable.get(baseNormalizedModelName, provider);
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
            ModelPrice normalizedMatch = ModelPriceTable.get(baseOriginalVersionName, provider);
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
            ModelPrice normalizedMatch = ModelPriceTable.get(baseNormalizedVersionName, provider);
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
}
