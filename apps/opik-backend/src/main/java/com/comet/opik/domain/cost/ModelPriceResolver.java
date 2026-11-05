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
    private static final Pattern DATE_SUFFIX_PATTERN = Pattern
            .compile("-\\d{4}-?(0[1-9]|1[0-2])-?(0[1-9]|[12]\\d|3[01])$");
    private static final Pattern VERSION_SUFFIX_PATTERN = Pattern.compile(":\\d+$");

    enum NameForm {
        EXACT,
        LOWER_CASED,
        NORMALIZED,
        DATE_STRIPPED,
        NORMALIZED_DATE_STRIPPED,
        VERSION_STRIPPED,
        NORMALIZED_VERSION_STRIPPED
    }

    @Builder
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
        String name = ModelPriceTable.parseModelName(modelName);

        Optional<PriceMatch> match = findExact(name, lookupOrder.getFirst())
                .or(() -> findAnySpelling(modelName, name, lookupOrder));
        match.ifPresentOrElse(
                found -> log.debug("Found model price, model '{}', provider '{}', name form '{}'", modelName,
                        found.provider(), found.nameForm()),
                () -> log.debug("No model price found, model '{}', provider '{}'", modelName, provider));
        return match;
    }

    // The exact name under the first provider is the first pair findUnder tries, and the one most spans hit, so
    // trying it alone first skips building the other spellings on every span insert and update.
    private Optional<PriceMatch> findExact(String name, String provider) {
        return Optional.ofNullable(ModelPriceTable.get(name, provider))
                .map(price -> PriceMatch.builder().price(price).provider(provider).nameForm(NameForm.EXACT).build());
    }

    private Optional<PriceMatch> findAnySpelling(String modelName, String name, List<String> lookupOrder) {
        Map<String, NameForm> spellings = spellings(name);
        return findUnder(lookupOrder, spellings)
                .or(() -> findUnderModelPrefix(modelName, lookupOrder, spellings));
    }

    // Duplicates are dropped by exact string, never ignoring case: most price keys are lower case, so a name that
    // differs from its lower-case spelling only in letter case ("GPT-5-MINI") still needs that lookup. The normalize and
    // strip helpers lower-case too, so a spelling that only changed case is labelled LOWER_CASED wherever it first
    // appears: "GPT-5.2" misses as sent and normalized, then its date strip removes nothing and finds "gpt-5.2". The
    // label never changes the order spellings are tried in.
    private Map<String, NameForm> spellings(String modelName) {
        String lowerCased = modelName.toLowerCase(Locale.ROOT);
        String normalized = normalize(modelName);
        Map<String, NameForm> spellings = new LinkedHashMap<>();
        spellings.putIfAbsent(modelName, NameForm.EXACT);
        add(spellings, normalized, lowerCased, NameForm.NORMALIZED);
        add(spellings, stripDateSuffix(modelName), lowerCased, NameForm.DATE_STRIPPED);
        spellings.putIfAbsent(stripDateSuffix(normalized), NameForm.NORMALIZED_DATE_STRIPPED);
        add(spellings, stripVersionSuffix(modelName), lowerCased, NameForm.VERSION_STRIPPED);
        spellings.putIfAbsent(stripVersionSuffix(normalized), NameForm.NORMALIZED_VERSION_STRIPPED);
        return spellings;
    }

    private void add(Map<String, NameForm> spellings, String spelling, String lowerCased, NameForm form) {
        spellings.putIfAbsent(spelling, spelling.equals(lowerCased) ? NameForm.LOWER_CASED : form);
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
