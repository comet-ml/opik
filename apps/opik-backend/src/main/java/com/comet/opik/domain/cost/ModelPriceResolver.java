package com.comet.opik.domain.cost;

import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds the price for a model name and provider. The organization's rows come first: every spelling of the name under
 * the provider, its siblings, then {@link CustomModelPrice#ANY_PROVIDER}. Then the built-in rows: the provider and the
 * providers LiteLLM files the same vendor's other models under, each tried with every spelling, and last the provider a
 * model-name prefix names.
 */
@Slf4j
@UtilityClass
class ModelPriceResolver {

    // Both hyphen-separated (2025-12-17) and compact (20251217) date suffixes. Anthropic ships
    // compact dates on every dated model id (claude-haiku-4-5-20251001), so without the optional
    // separators a compact-dated name only ever prices when it is present verbatim in the price
    // table -- any name needing normalization (dot form, an `alias_of` entry, or a release the
    // daily LiteLLM sync has not picked up yet) silently fell through to a zero cost.
    private static final Pattern DATE_SUFFIX_PATTERN = Pattern
            .compile("-\\d{4}-?(0[1-9]|1[0-2])-?(0[1-9]|[12]\\d|3[01])$");
    private static final Pattern VERSION_SUFFIX_PATTERN = Pattern.compile(":\\d+$");

    enum NameForm {
        EXACT,
        NORMALIZED,
        DATE_STRIPPED,
        NORMALIZED_DATE_STRIPPED,
        VERSION_STRIPPED,
        NORMALIZED_VERSION_STRIPPED
    }

    /**
     * The price to bill, and the row the span matched: the organization's {@code customRow}, else a built-in one. For
     * an alias, {@code targetRow} is the organization's row it led to, if it led to one.
     */
    @Builder(toBuilder = true)
    record PriceMatch(@NonNull ModelPrice price, @NonNull String provider, @NonNull NameForm nameForm,
            @Nullable CustomModelPrice customRow, @Nullable CustomModelPrice targetRow) {
    }

    private record CustomMatch(CustomModelPrice row, String provider, NameForm nameForm) {
    }

    Optional<PriceMatch> resolve(@Nullable String modelName, @Nullable String provider) {
        return resolve(modelName, provider, PriceLayers.BUILT_IN_ONLY);
    }

    Optional<PriceMatch> resolve(@Nullable String modelName, @Nullable String provider, @NonNull PriceLayers layers) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }
        Optional<PriceMatch> match = resolve(modelName, ProviderRegistry.resolve(provider, modelName), layers, true);
        if (match.isEmpty()) {
            log.debug("No model price found, model '{}', provider '{}'", modelName, provider);
        }
        return match;
    }

    private Optional<PriceMatch> resolve(String modelName, Optional<String> resolvedProvider, PriceLayers layers,
            boolean followAliases) {
        List<String> lookupOrder = resolvedProvider.map(ProviderRegistry::priceLookupOrder).orElse(List.of());
        // The price keys are stored without a provider prefix (LiteLLM sends "openai/gpt-4o"), so every spelling is
        // derived from the prefix-free name.
        Map<String, NameForm> spellings = spellings(ModelPriceTable.parseModelName(modelName));
        Optional<CustomMatch> custom = findCustom(layers, lookupOrder, spellings, followAliases);
        if (custom.isPresent() && custom.get().row().kind() == CustomModelPrice.Kind.ALIAS) {
            CustomMatch alias = custom.get();
            return resolveAliasTarget(alias.row(), resolvedProvider, layers)
                    .map(target -> target.toBuilder()
                            .provider(alias.provider())
                            .nameForm(alias.nameForm())
                            .customRow(alias.row())
                            .targetRow(target.customRow())
                            .build())
                    .or(() -> findBuiltIn(modelName, resolvedProvider, lookupOrder, spellings));
        }
        Optional<PriceMatch> builtIn = findBuiltIn(modelName, resolvedProvider, lookupOrder, spellings);
        if (custom.isEmpty()) {
            return builtIn;
        }
        CustomMatch fixed = custom.get();
        ModelPrice base = builtIn.map(PriceMatch::price).orElseGet(ModelPrice::empty);
        return Optional.of(PriceMatch.builder()
                .price(ModelPriceMerger.merge(base, fixed.row()))
                .provider(fixed.provider())
                .nameForm(fixed.nameForm())
                .customRow(fixed.row())
                .build());
    }

    private Optional<PriceMatch> findBuiltIn(String modelName, Optional<String> resolvedProvider,
            List<String> lookupOrder, Map<String, NameForm> spellings) {
        if (resolvedProvider.isEmpty()) {
            return Optional.empty();
        }
        return findUnder(lookupOrder, spellings).or(() -> findUnderModelPrefix(modelName, lookupOrder, spellings));
    }

    // The target is looked up with alias rows ignored: one hop, so an alias can never reach another alias or itself.
    private Optional<PriceMatch> resolveAliasTarget(CustomModelPrice alias, Optional<String> spanProvider,
            PriceLayers layers) {
        String targetModel = alias.aliasModelName();
        if (StringUtils.isBlank(targetModel)) {
            return Optional.empty();
        }
        Optional<String> targetProvider = StringUtils.isBlank(alias.aliasProvider())
                ? spanProvider
                : ProviderRegistry.resolve(alias.aliasProvider(), targetModel);
        return resolve(targetModel, targetProvider, layers, false);
    }

    private Optional<CustomMatch> findCustom(PriceLayers layers, List<String> lookupOrder,
            Map<String, NameForm> spellings, boolean followAliases) {
        if (!layers.hasRows()) {
            return Optional.empty();
        }
        for (String candidateProvider : lookupOrder) {
            Optional<CustomMatch> match = findCustomUnder(layers, candidateProvider, spellings, followAliases);
            if (match.isPresent()) {
                return match;
            }
        }
        return findCustomUnder(layers, CustomModelPrice.ANY_PROVIDER, spellings, followAliases);
    }

    private Optional<CustomMatch> findCustomUnder(PriceLayers layers, String provider,
            Map<String, NameForm> spellings, boolean followAliases) {
        for (Map.Entry<String, NameForm> spelling : spellings.entrySet()) {
            CustomModelPrice row = layers.find(spelling.getKey(), provider);
            if (row != null && (followAliases || row.kind() == CustomModelPrice.Kind.FIXED)) {
                return Optional.of(new CustomMatch(row, provider, spelling.getValue()));
            }
        }
        return Optional.empty();
    }

    // Duplicates are dropped by exact string, never ignoring case: most price keys are lower case, so a name that
    // differs from its lower-case spelling only in letter case ("GPT-5-MINI") still needs that lookup.
    private Map<String, NameForm> spellings(String modelName) {
        String normalized = normalize(modelName);
        Map<String, NameForm> spellings = new LinkedHashMap<>();
        spellings.putIfAbsent(modelName, NameForm.EXACT);
        spellings.putIfAbsent(normalized, NameForm.NORMALIZED);
        spellings.putIfAbsent(stripDateSuffix(modelName), NameForm.DATE_STRIPPED);
        spellings.putIfAbsent(stripDateSuffix(normalized), NameForm.NORMALIZED_DATE_STRIPPED);
        spellings.putIfAbsent(stripVersionSuffix(modelName), NameForm.VERSION_STRIPPED);
        spellings.putIfAbsent(stripVersionSuffix(normalized), NameForm.NORMALIZED_VERSION_STRIPPED);
        return spellings;
    }

    private Optional<PriceMatch> findUnder(List<String> providers, Map<String, NameForm> spellings) {
        for (String candidateProvider : providers) {
            for (Map.Entry<String, NameForm> spelling : spellings.entrySet()) {
                ModelPrice price = ModelPriceTable.get(spelling.getKey(), candidateProvider);
                if (price != null) {
                    return Optional.of(PriceMatch.builder()
                            .price(price)
                            .provider(candidateProvider)
                            .nameForm(spelling.getValue())
                            .build());
                }
            }
        }
        return Optional.empty();
    }

    // An aggregator can carry a model whose price row lives under its origin provider: BudgetGuard prices
    // "perplexity/sonar" with provider "openrouter", and the row is filed under "perplexity". This runs only after
    // every primary lookup missed, so it never changes a match a caller's provider already finds.
    private Optional<PriceMatch> findUnderModelPrefix(String modelName, List<String> lookupOrder,
            Map<String, NameForm> spellings) {
        int prefixSlash = modelName.indexOf('/');
        if (prefixSlash <= 0) {
            return Optional.empty();
        }
        String providerFromPrefix = ProviderRegistry.toLiteLlmName(modelName.substring(0, prefixSlash));
        if (lookupOrder.contains(providerFromPrefix)) {
            return Optional.empty();
        }
        return findUnder(ProviderRegistry.priceLookupOrder(providerFromPrefix), spellings);
    }

    private String normalize(String modelName) {
        return modelName.replace('.', '-').toLowerCase(Locale.ROOT);
    }

    private String stripDateSuffix(String modelName) {
        return DATE_SUFFIX_PATTERN.matcher(modelName.toLowerCase(Locale.ROOT)).replaceFirst("");
    }

    private String stripVersionSuffix(String modelName) {
        return VERSION_SUFFIX_PATTERN.matcher(modelName.toLowerCase(Locale.ROOT)).replaceFirst("");
    }
}
