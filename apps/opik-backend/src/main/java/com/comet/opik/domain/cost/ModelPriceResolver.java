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

/**
 * Finds the built-in price row for a model name and provider: the provider, then the providers LiteLLM files the same
 * vendor's other models under, each tried with every spelling of the name, and last the provider a model-name prefix
 * names.
 */
@Slf4j
@UtilityClass
class ModelPriceResolver {

    // Both hyphen-separated (2025-12-17) and compact (20251217) date suffixes. Anthropic ships
    // compact dates on every dated model id (claude-haiku-4-5-20251001), so without the optional
    // separators a compact-dated name only ever prices when it is present verbatim in the price
    // table -- any name needing normalization (dot form, an `alias_of` entry, or a release the
    // daily LiteLLM sync has not picked up yet) silently fell through to a zero cost.
    private static final String DATE_SUFFIX_PATTERN = "-\\d{4}-?(0[1-9]|1[0-2])-?(0[1-9]|[12]\\d|3[01])$";
    private static final String VERSION_SUFFIX_PATTERN = ":\\d+$";

    enum NameForm {
        EXACT,
        NORMALIZED,
        DATE_STRIPPED,
        NORMALIZED_DATE_STRIPPED,
        VERSION_STRIPPED,
        NORMALIZED_VERSION_STRIPPED
    }

    @Builder(toBuilder = true)
    record PriceMatch(@NonNull ModelPrice price, @NonNull String provider, @NonNull NameForm nameForm) {
    }

    Optional<PriceMatch> resolve(@Nullable String modelName, @Nullable String provider) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }
        Optional<String> resolvedProvider = ProviderRegistry.resolve(provider, modelName);
        if (resolvedProvider.isEmpty()) {
            return Optional.empty();
        }
        List<String> lookupOrder = ProviderRegistry.priceLookupOrder(resolvedProvider.get());
        // The price keys are stored without a provider prefix (LiteLLM sends "openai/gpt-4o"), so every spelling is
        // derived from the prefix-free name.
        Map<String, NameForm> spellings = spellings(ModelPriceTable.parseModelName(modelName));

        Optional<PriceMatch> match = findUnder(lookupOrder, spellings)
                .or(() -> findUnderModelPrefix(modelName, lookupOrder, spellings));
        if (match.isEmpty()) {
            log.debug("No model price found, model '{}', provider '{}'", modelName, provider);
        }
        return match;
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
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(DATE_SUFFIX_PATTERN, "");
    }

    private String stripVersionSuffix(String modelName) {
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(VERSION_SUFFIX_PATTERN, "");
    }
}
