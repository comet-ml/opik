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
        LOWER_CASED,
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

    @Builder(toBuilder = true)
    private record CustomMatch(@NonNull CustomModelPrice row, @NonNull String provider, @NonNull NameForm nameForm) {
    }

    Optional<PriceMatch> resolve(@Nullable String modelName, @Nullable String provider) {
        return resolve(modelName, provider, PriceLayers.BUILT_IN_ONLY);
    }

    Optional<PriceMatch> resolve(@Nullable String modelName, @Nullable String provider, @NonNull PriceLayers layers) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }
        return resolveForProvider(modelName, ProviderRegistry.resolve(provider, modelName), layers);
    }

    // CostService needs the resolved provider for usage too, so it resolves it once per span and passes it in here.
    Optional<PriceMatch> resolveForProvider(@Nullable String modelName, @NonNull Optional<String> resolvedProvider,
            @NonNull PriceLayers layers) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }
        Optional<PriceMatch> match = findPrice(modelName, resolvedProvider, layers, true);
        if (log.isDebugEnabled()) {
            match.ifPresentOrElse(
                    found -> log.debug("Found model price, model '{}', provider '{}', name form '{}'", modelName,
                            found.provider(), found.nameForm()),
                    () -> log.debug("No model price found, model '{}', provider '{}'", modelName,
                            resolvedProvider.orElse(null)));
        }
        return match;
    }

    Optional<CustomModelPrice> matchingCustomRow(@NonNull String modelName, @NonNull String provider,
            @NonNull PriceLayers layers) {
        List<String> lookupOrder = ProviderRegistry.resolve(provider, modelName)
                .map(ProviderRegistry::priceLookupOrder)
                .orElse(List.of());
        return firstCustomRows(modelName, List.of(lookupOrder), layers).stream().findFirst();
    }

    /**
     * The organization row, aliases included, that a span named {@code modelName} matches first under each provider
     * lookup order, distinct. An empty lookup order stands for a provider with no rows of its own. The spellings are
     * built once for all of them.
     */
    List<CustomModelPrice> firstCustomRows(@NonNull String modelName, @NonNull List<List<String>> lookupOrders,
            @NonNull PriceLayers layers) {
        if (!layers.hasRows()) {
            return List.of();
        }
        Map<String, NameForm> spellings = spellings(ModelPriceTable.parseModelName(modelName));
        return lookupOrders.stream()
                .map(lookupOrder -> findCustom(layers, lookupOrder, spellings, true))
                .flatMap(Optional::stream)
                .map(CustomMatch::row)
                .distinct()
                .toList();
    }

    /**
     * The built-in price a span of this model gets under each provider that files one of its spellings, sorted by
     * provider: how an any-provider row's spans are priced without it. The providers come from an index, so this
     * doesn't try every provider.
     */
    List<PriceMatch> builtInMatchesUnderEveryProvider(@NonNull String modelName) {
        Map<String, NameForm> spellings = spellings(ModelPriceTable.parseModelName(modelName));
        return spellings.keySet().stream()
                .flatMap(spelling -> ModelPriceTable.providersOf(spelling).stream())
                .distinct()
                .sorted()
                .map(provider -> findBuiltIn(modelName, Optional.of(provider),
                        ProviderRegistry.priceLookupOrder(provider), spellings))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<PriceMatch> findPrice(String modelName, Optional<String> resolvedProvider, PriceLayers layers,
            boolean followAliases) {
        List<String> lookupOrder = resolvedProvider.map(ProviderRegistry::priceLookupOrder).orElse(List.of());
        // The price keys are stored without a provider prefix (LiteLLM sends "openai/gpt-4o"), so every spelling is
        // derived from the prefix-free name.
        String name = ModelPriceTable.parseModelName(modelName);
        if (!layers.hasRows() && !lookupOrder.isEmpty()) {
            Optional<PriceMatch> exact = findExact(name, lookupOrder.getFirst());
            if (exact.isPresent()) {
                return exact;
            }
        }
        Map<String, NameForm> spellings = spellings(name);
        Optional<CustomMatch> custom = findCustom(layers, lookupOrder, spellings, followAliases);
        if (custom.isPresent() && custom.get().row().kind() == CustomModelPrice.Kind.ALIAS) {
            CustomMatch alias = custom.get();
            Optional<PriceMatch> target = resolveAliasTarget(alias.row(), resolvedProvider, layers);
            // Token rates would bill a model priced per character or per second for tokens its spans never log.
            if (target.isPresent() && !target.get().price().isPricedPerUnit()) {
                Optional<PriceMatch> builtIn = findBuiltIn(modelName, resolvedProvider, lookupOrder, spellings);
                if (builtIn.map(match -> match.price().isPricedPerUnit()).orElse(false)) {
                    return builtIn;
                }
            }
            return target
                    .map(match -> match.toBuilder()
                            .provider(alias.provider())
                            .nameForm(alias.nameForm())
                            .customRow(alias.row())
                            .targetRow(match.customRow())
                            .build())
                    .or(() -> findBuiltIn(modelName, resolvedProvider, lookupOrder, spellings));
        }
        Optional<PriceMatch> builtIn = findBuiltIn(modelName, resolvedProvider, lookupOrder, spellings);
        // A fixed row only sets token rates, which a model billed per character or per second never reads.
        if (custom.isEmpty() || builtIn.map(match -> match.price().isPricedPerUnit()).orElse(false)) {
            return builtIn;
        }
        CustomMatch fixed = custom.get();
        // A row that sets no rate, over no built-in price, prices nothing: billing it would charge 0 as the
        // organization's price, and an alias to it would skip the span's own built-in row.
        if (builtIn.isEmpty() && !setsAnyRate(fixed.row())) {
            return Optional.empty();
        }
        ModelPrice base = builtIn.map(PriceMatch::price).orElseGet(ModelPrice::empty);
        return Optional.of(PriceMatch.builder()
                .price(ModelPriceMerger.merge(base, fixed.row()))
                .provider(fixed.provider())
                .nameForm(fixed.nameForm())
                .customRow(fixed.row())
                .build());
    }

    // With no organization rows, the exact name under the first provider is the first pair findBuiltIn tries, and the
    // one most spans hit, so trying it alone first skips building the other spellings on every span insert and update.
    private Optional<PriceMatch> findExact(String name, String provider) {
        return Optional.ofNullable(ModelPriceTable.get(name, provider))
                .map(price -> PriceMatch.builder().price(price).provider(provider).nameForm(NameForm.EXACT).build());
    }

    private boolean setsAnyRate(CustomModelPrice row) {
        return row.inputPerMillion() != null || row.outputPerMillion() != null || row.cacheReadPerMillion() != null
                || row.cacheWritePerMillion() != null;
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
        return findPrice(targetModel, targetProvider, layers, false);
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
                return Optional.of(CustomMatch.builder()
                        .row(row)
                        .provider(provider)
                        .nameForm(spelling.getValue())
                        .build());
            }
        }
        return Optional.empty();
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
