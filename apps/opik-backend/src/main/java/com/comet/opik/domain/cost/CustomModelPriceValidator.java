package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks an organization's price rows as sent, before any is saved: every problem of every row is reported at once,
 * like a form shows each invalid field. Rows are normalized the way span names are looked up, so a saved row matches
 * the spans it is meant for. Warnings flag rows that save but likely don't do what was meant.
 */
@UtilityClass
public class CustomModelPriceValidator {

    public static final int MAX_ROWS = 1_000;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final int MAX_DECIMALS = 10;
    private static final BigDecimal MAX_PRICE_PER_MILLION = new BigDecimal("100000");
    private static final BigDecimal OUT_OF_RANGE_FACTOR = BigDecimal.TEN;

    private static final String INPUT_PRICE = "input_price";
    private static final String OUTPUT_PRICE = "output_price";
    private static final String CACHE_READ_PRICE = "cache_read_price";
    private static final String CACHE_WRITE_PRICE = "cache_write_price";
    private static final String ALIAS_OF = "alias_of";
    private static final List<String> PRICE_FIELDS = List.of(INPUT_PRICE, OUTPUT_PRICE, CACHE_READ_PRICE,
            CACHE_WRITE_PRICE);
    private static final Set<String> FIELDS = Set.of("model", "provider", "kind", INPUT_PRICE, OUTPUT_PRICE,
            CACHE_READ_PRICE, CACHE_WRITE_PRICE, ALIAS_OF, "note");
    // What a listed row also carries, so rows read from the API can be sent back unchanged.
    private static final Set<String> READ_ONLY_FIELDS = Set.of("id", "created_at", "created_by", "last_updated_at",
            "last_updated_by");
    private static final Set<String> ALIAS_OF_FIELDS = Set.of("model", "provider");

    @Builder(toBuilder = true)
    public record Problem(@Nullable Integer row, @Nullable String field, @NonNull String code,
            @NonNull String message) {
    }

    @Builder(toBuilder = true)
    public record ValidRow(@NonNull CustomModelPrice price, @Nullable String note) {
    }

    /** The normalized rows to save, only when there is no error. */
    @Builder(toBuilder = true)
    public record Result(@NonNull List<ValidRow> rows, @NonNull List<Problem> errors,
            @NonNull List<Problem> warnings) {

        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    @Builder(toBuilder = true)
    private record ParsedRow(int index, @NonNull String sentModel, @Nullable String sentProvider,
            @NonNull CustomModelPrice price, @Nullable String note) {
    }

    @Builder(toBuilder = true)
    private record RowKey(@NonNull String modelName, @NonNull String provider) {
    }

    public Result validate(@NonNull List<JsonNode> rows) {
        var errors = new ArrayList<Problem>();
        if (rows.size() > MAX_ROWS) {
            errors.add(Problem.builder()
                    .code("too_many_rows")
                    .message("At most %d rows can be saved, got %d".formatted(MAX_ROWS, rows.size()))
                    .build());
        }
        var parsedRows = new ArrayList<ParsedRow>();
        Map<RowKey, Integer> firstIndexByKey = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            Optional<ParsedRow> parsed = parse(index, rows.get(index), errors);
            if (parsed.isEmpty()) {
                continue;
            }
            CustomModelPrice price = parsed.get().price();
            Integer firstIndex = firstIndexByKey.putIfAbsent(keyOf(price), index);
            if (firstIndex != null) {
                errors.add(Problem.builder()
                        .row(index)
                        .field("model")
                        .code("duplicate_row")
                        .message("Row %d already prices '%s' on %s".formatted(firstIndex, price.modelName(),
                                describeProvider(price.provider())))
                        .build());
                continue;
            }
            parsedRows.add(parsed.get());
        }
        checkAliasTargets(parsedRows, errors);
        var warnings = warnings(parsedRows);
        List<ValidRow> validRows = errors.isEmpty()
                ? parsedRows.stream()
                        .map(row -> ValidRow.builder().price(row.price()).note(row.note()).build())
                        .toList()
                : List.of();
        return Result.builder()
                .rows(validRows)
                .errors(List.copyOf(errors))
                .warnings(warnings)
                .build();
    }

    private Optional<ParsedRow> parse(int index, JsonNode node, List<Problem> errors) {
        if (node == null || !node.isObject()) {
            errors.add(Problem.builder()
                    .row(index)
                    .code("not_an_object")
                    .message("Each row must be a JSON object")
                    .build());
            return Optional.empty();
        }
        int errorsBefore = errors.size();
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field) && !READ_ONLY_FIELDS.contains(field)) {
                errors.add(Problem.builder()
                        .row(index)
                        .field(field)
                        .code("unknown_field")
                        .message("Unknown field '%s'".formatted(field))
                        .build());
            }
        });

        String sentModel = text(index, node, "model", errors);
        String trimmedModel = name(index, "model", sentModel, errors);
        String modelName = trimmedModel == null ? null : ModelPriceTable.rowModelName(trimmedModel);
        if (!isSet(node, "model") || (sentModel != null && sentModel.isBlank())) {
            errors.add(Problem.builder()
                    .row(index)
                    .field("model")
                    .code("missing_model")
                    .message("Every row needs a model")
                    .build());
        } else if (modelName != null && modelName.isEmpty()) {
            errors.add(Problem.builder()
                    .row(index)
                    .field("model")
                    .code("missing_model")
                    .message("The model has no name after its provider")
                    .build());
        }
        String sentProvider = text(index, node, "provider", errors);
        String trimmedProvider = name(index, "provider", sentProvider, errors);
        String provider = ProviderRegistry.resolve(trimmedProvider, trimmedModel).orElse(CustomModelPrice.ANY_PROVIDER);

        CustomModelPrice.Kind kind = kind(index, node, errors);
        var prices = new HashMap<String, BigDecimal>();
        PRICE_FIELDS.forEach(field -> {
            BigDecimal price = price(index, node, field, errors);
            if (price != null) {
                prices.put(field, price);
            }
        });
        String note = text(index, node, "note", errors);
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            errors.add(Problem.builder()
                    .row(index)
                    .field("note")
                    .code("note_too_long")
                    .message("The note is %d characters, at most %d".formatted(note.length(), MAX_NOTE_LENGTH))
                    .build());
        }

        var priceBuilder = CustomModelPrice.builder()
                .modelName(Optional.ofNullable(modelName).orElse(""))
                .provider(provider)
                .kind(Optional.ofNullable(kind).orElse(CustomModelPrice.Kind.FIXED))
                .inputPerMillion(prices.get(INPUT_PRICE))
                .outputPerMillion(prices.get(OUTPUT_PRICE))
                .cacheReadPerMillion(prices.get(CACHE_READ_PRICE))
                .cacheWritePerMillion(prices.get(CACHE_WRITE_PRICE));
        if (kind == CustomModelPrice.Kind.FIXED) {
            checkFixed(index, node, errors);
        } else if (kind == CustomModelPrice.Kind.ALIAS) {
            checkAlias(index, node, priceBuilder, errors);
        }
        if (errors.size() != errorsBefore) {
            return Optional.empty();
        }
        return Optional.of(ParsedRow.builder()
                .index(index)
                .sentModel(sentModel)
                .sentProvider(sentProvider)
                .price(priceBuilder.build())
                .note(note)
                .build());
    }

    private void checkFixed(int index, JsonNode node, List<Problem> errors) {
        if (isSet(node, ALIAS_OF)) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(ALIAS_OF)
                    .code("field_not_for_kind")
                    .message("A fixed row can't have alias_of")
                    .build());
        }
        if (PRICE_FIELDS.stream().noneMatch(field -> isSet(node, field))) {
            errors.add(Problem.builder()
                    .row(index)
                    .code("no_price")
                    .message("A fixed row needs at least one price")
                    .build());
        }
    }

    private void checkAlias(int index, JsonNode node, CustomModelPrice.CustomModelPriceBuilder priceBuilder,
            List<Problem> errors) {
        PRICE_FIELDS.stream().filter(field -> isSet(node, field)).forEach(field -> errors.add(Problem.builder()
                .row(index)
                .field(field)
                .code("field_not_for_kind")
                .message("An alias row prices like its target, so it can't have %s".formatted(field))
                .build()));
        JsonNode aliasOf = node.get(ALIAS_OF);
        if (aliasOf == null || !aliasOf.isObject()) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(ALIAS_OF)
                    .code("missing_alias_target")
                    .message("An alias row needs alias_of with the model it prices like")
                    .build());
            return;
        }
        aliasOf.fieldNames().forEachRemaining(field -> {
            if (!ALIAS_OF_FIELDS.contains(field)) {
                errors.add(Problem.builder()
                        .row(index)
                        .field(ALIAS_OF + "." + field)
                        .code("unknown_field")
                        .message("Unknown field '%s.%s'".formatted(ALIAS_OF, field))
                        .build());
            }
        });
        String targetModel = name(index, ALIAS_OF + ".model", text(index, aliasOf, "model", errors), errors);
        if (targetModel == null || ModelPriceTable.rowModelName(targetModel).isEmpty()) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(ALIAS_OF + ".model")
                    .code("missing_alias_target")
                    .message("An alias row needs the model it prices like")
                    .build());
            return;
        }
        String targetProvider = name(index, ALIAS_OF + ".provider", text(index, aliasOf, "provider", errors),
                errors);
        priceBuilder.aliasModelName(ModelPriceTable.rowModelName(targetModel))
                .aliasProvider(ProviderRegistry.resolve(targetProvider, targetModel)
                        .orElse(CustomModelPrice.SPAN_PROVIDER));
    }

    private void checkAliasTargets(List<ParsedRow> rows, List<Problem> errors) {
        List<CustomModelPrice> prices = rows.stream().map(ParsedRow::price).toList();
        List<CustomModelPrice> fixedPrices = prices.stream()
                .filter(price -> price.kind() == CustomModelPrice.Kind.FIXED)
                .toList();
        var rowsLayer = PriceLayers.of("validation", 0, prices);
        var fixedPriceLayer = PriceLayers.of("validation", 0, fixedPrices);
        // A fixed row can be filed under a provider no built-in price uses, so its spans still price.
        Set<String> spanProviders = new HashSet<>(ModelPriceTable.pricedProviders());
        fixedPrices.stream().map(CustomModelPrice::provider).filter(provider -> !provider.isEmpty())
                .forEach(spanProviders::add);
        for (ParsedRow row : rows) {
            if (row.price().kind() == CustomModelPrice.Kind.ALIAS) {
                aliasTargetProblem(row, rowsLayer, fixedPriceLayer, spanProviders).ifPresent(errors::add);
            }
        }
    }

    private Optional<Problem> aliasTargetProblem(ParsedRow row, PriceLayers rowsLayer, PriceLayers fixedPriceLayer,
            Set<String> spanProviders) {
        CustomModelPrice alias = row.price();
        boolean underSpanProvider = CustomModelPrice.SPAN_PROVIDER.equals(alias.aliasProvider());
        String targetProvider = underSpanProvider ? alias.provider() : alias.aliasProvider();
        var targetKey = RowKey.builder().modelName(alias.aliasModelName()).provider(targetProvider).build();
        if (targetKey.equals(keyOf(alias))) {
            return Optional.of(Problem.builder()
                    .row(row.index())
                    .field(ALIAS_OF)
                    .code("alias_target_is_itself")
                    .message("An alias row can't price like itself")
                    .build());
        }
        // An any-provider alias whose target names no provider is looked up under each span's own provider.
        Set<String> targetProviders = underSpanProvider && CustomModelPrice.ANY_PROVIDER.equals(alias.provider())
                ? spanProviders
                : Set.of(targetProvider);
        // The target is priced with alias rows skipped, so if a span named like the target would match another alias,
        // the two bill differently. Spans match any spelling (a date suffix stripped, ...), not only the exact name.
        // Matching this row itself is fine: both then end at the target's own price.
        Optional<CustomModelPrice> targetAlias = targetProviders.stream()
                .map(provider -> ModelPriceResolver.matchingCustomRow(alias.aliasModelName(), provider, rowsLayer))
                .flatMap(Optional::stream)
                .filter(target -> target.kind() == CustomModelPrice.Kind.ALIAS && !target.equals(alias))
                .findFirst();
        if (targetAlias.isPresent()) {
            return Optional.of(Problem.builder()
                    .row(row.index())
                    .field(ALIAS_OF)
                    .code("alias_target_is_alias")
                    .message("'%s' is an alias too: point this row at a model with a price"
                            .formatted(targetAlias.get().modelName()))
                    .build());
        }
        if (!pricedUnderSomeProvider(alias.aliasModelName(), targetProviders, fixedPriceLayer)) {
            return Optional.of(Problem.builder()
                    .row(row.index())
                    .field(ALIAS_OF)
                    .code("alias_target_unpriced")
                    .message("Neither the built-in prices nor these rows price '%s' on %s".formatted(
                            alias.aliasModelName(), describeProvider(targetProvider)))
                    .build());
        }
        return Optional.empty();
    }

    private List<Problem> warnings(List<ParsedRow> rows) {
        var warnings = new ArrayList<Problem>();
        for (ParsedRow row : rows) {
            CustomModelPrice price = row.price();
            if (!price.modelName().equals(row.sentModel()) || !price.provider().equals(
                    Optional.ofNullable(row.sentProvider()).orElse(CustomModelPrice.ANY_PROVIDER))) {
                warnings.add(Problem.builder()
                        .row(row.index())
                        .field("model")
                        .code("name_normalized")
                        .message("Saved as '%s' on %s, the form spans are looked up in".formatted(price.modelName(),
                                describeProvider(price.provider())))
                        .build());
            }
            if (!price.provider().isEmpty() && !ModelPriceTable.isPricedProvider(price.provider())) {
                warnings.add(Problem.builder()
                        .row(row.index())
                        .field("provider")
                        .code("provider_unpriced")
                        .message("No built-in price is filed under '%s', so only these rows price its spans"
                                .formatted(price.provider()))
                        .build());
            }
            Optional<ModelPrice> builtIn = price.provider().isEmpty() ? Optional.empty() : builtInBase(row);
            if (price.kind() == CustomModelPrice.Kind.ALIAS) {
                boolean shadows = price.provider().isEmpty()
                        ? pricedUnderSomeProvider(price.modelName(), ModelPriceTable.pricedProviders(),
                                PriceLayers.BUILT_IN_ONLY)
                        : builtIn.isPresent();
                if (shadows) {
                    warnings.add(Problem.builder()
                            .row(row.index())
                            .field("model")
                            .code("shadows_builtin")
                            .message("'%s' has a built-in price, which this alias replaces"
                                    .formatted(price.modelName()))
                            .build());
                }
            } else if (!price.provider().isEmpty()) {
                builtIn.ifPresentOrElse(base -> warnings.addAll(fixedRowWarnings(row.index(), price, base)),
                        () -> warnings.add(Problem.builder()
                                .row(row.index())
                                .field("model")
                                .code("no_base")
                                .message(("No built-in price for '%s' on %s: rates left unset bill nothing, and "
                                        + "cached tokens bill at the input price").formatted(price.modelName(),
                                                describeProvider(price.provider())))
                                .build()));
            }
        }
        return List.copyOf(warnings);
    }

    // Saving strips a vendor prefix that spans keep: a span named openai/gpt-4o-mini on OpenRouter finds a built-in
    // price through it, so the row's base is looked up under each spelling such a span could have.
    private Optional<ModelPrice> builtInBase(ParsedRow row) {
        CustomModelPrice price = row.price();
        return Stream.of(Stream.of(price.modelName(), row.sentModel().trim()),
                ModelPriceTable.prefixedModelNames(price.modelName(), price.provider()).stream())
                .flatMap(names -> names)
                .map(name -> ModelPriceResolver.resolve(name, price.provider()))
                .flatMap(Optional::stream)
                .map(ModelPriceResolver.PriceMatch::price)
                .findFirst();
    }

    private List<Problem> fixedRowWarnings(int index, CustomModelPrice price, ModelPrice base) {
        if (base.isPricedPerUnit()) {
            return List.of(Problem.builder()
                    .row(index)
                    .field("model")
                    .code("priced_per_unit")
                    .message("'%s' is priced per second or per character, so token prices don't apply"
                            .formatted(price.modelName()))
                    .build());
        }
        var warnings = new ArrayList<Problem>();
        outOfRange(index, INPUT_PRICE, price.inputPerMillion(), base.inputPrice()).ifPresent(warnings::add);
        outOfRange(index, OUTPUT_PRICE, price.outputPerMillion(), base.outputPrice()).ifPresent(warnings::add);
        outOfRange(index, CACHE_READ_PRICE, price.cacheReadPerMillion(), base.cacheReadInputTokenPrice())
                .ifPresent(warnings::add);
        outOfRange(index, CACHE_WRITE_PRICE, price.cacheWritePerMillion(), base.cacheCreationInputTokenPrice())
                .ifPresent(warnings::add);
        return warnings;
    }

    // Over 10x or under a tenth of the built-in price, which catches a per-token price typed where per 1M is meant.
    private Optional<Problem> outOfRange(int index, String field, @Nullable BigDecimal perMillion,
            BigDecimal builtInPerToken) {
        if (perMillion == null || perMillion.signum() <= 0 || builtInPerToken.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal builtInPerMillion = builtInPerToken.movePointRight(6);
        if (perMillion.compareTo(builtInPerMillion.multiply(OUT_OF_RANGE_FACTOR)) <= 0
                && perMillion.multiply(OUT_OF_RANGE_FACTOR).compareTo(builtInPerMillion) >= 0) {
            return Optional.empty();
        }
        return Optional.of(Problem.builder()
                .row(index)
                .field(field)
                .code("price_out_of_range")
                .message("%s per 1M tokens is far from the built-in %s: prices are per 1M tokens, not per token"
                        .formatted(perMillion.stripTrailingZeros().toPlainString(),
                                builtInPerMillion.stripTrailingZeros().toPlainString()))
                .build());
    }

    private @Nullable CustomModelPrice.Kind kind(int index, JsonNode node, List<Problem> errors) {
        String kind = text(index, node, "kind", errors);
        if (StringUtils.isBlank(kind)) {
            errors.add(Problem.builder()
                    .row(index)
                    .field("kind")
                    .code("missing_kind")
                    .message("Every row needs a kind: fixed or alias")
                    .build());
            return null;
        }
        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case "fixed" -> CustomModelPrice.Kind.FIXED;
            case "alias" -> CustomModelPrice.Kind.ALIAS;
            default -> {
                errors.add(Problem.builder()
                        .row(index)
                        .field("kind")
                        .code("unknown_kind")
                        .message("The kind is fixed or alias, got '%s'".formatted(kind))
                        .build());
                yield null;
            }
        };
    }

    private @Nullable BigDecimal price(int index, JsonNode node, String field, List<Problem> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        BigDecimal price;
        try {
            if (value.isNumber()) {
                price = value.decimalValue();
            } else if (value.isTextual()) {
                price = new BigDecimal(value.asText().trim());
            } else {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(field)
                    .code("not_a_number")
                    .message("%s must be a number".formatted(field))
                    .build());
            return null;
        }
        int errorsBefore = errors.size();
        if (price.stripTrailingZeros().scale() > MAX_DECIMALS) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(field)
                    .code("too_many_decimals")
                    .message("%s has more than %d decimals".formatted(field, MAX_DECIMALS))
                    .build());
        }
        if (price.compareTo(MAX_PRICE_PER_MILLION) > 0) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(field)
                    .code("price_too_high")
                    .message("%s is over %s per 1M tokens".formatted(field, MAX_PRICE_PER_MILLION.toPlainString()))
                    .build());
        }
        boolean cachePrice = CACHE_READ_PRICE.equals(field) || CACHE_WRITE_PRICE.equals(field);
        // The calculator bills cached tokens at the input price when the cache price isn't above 0, so 0 can't mean
        // free here: leave the field out to inherit the built-in cache price.
        if (cachePrice && price.signum() <= 0) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(field)
                    .code("cache_price_not_positive")
                    .message("%s must be above 0; leave it out to keep the built-in cache price".formatted(field))
                    .build());
        } else if (price.signum() < 0) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(field)
                    .code("negative_price")
                    .message("%s can't be negative".formatted(field))
                    .build());
        }
        return errors.size() == errorsBefore ? price : null;
    }

    private @Nullable String text(int index, JsonNode node, String field, List<Problem> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(field)
                    .code("not_a_string")
                    .message("%s must be a string".formatted(field))
                    .build());
            return null;
        }
        return value.asText();
    }

    // Whitespace and control characters at either end, as a pasted value often has, are trimmed and reported by the
    // name_normalized warning. Inside a name they are an error: no span name could match it.
    private @Nullable String name(int index, String field, @Nullable String sent, List<Problem> errors) {
        if (sent == null) {
            return null;
        }
        String trimmed = sent.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(field)
                    .code("name_too_long")
                    .message("%s is %d characters, at most %d".formatted(field, trimmed.length(), MAX_NAME_LENGTH))
                    .build());
            return null;
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            errors.add(Problem.builder()
                    .row(index)
                    .field(field)
                    .code("control_characters")
                    .message("%s can't contain control characters".formatted(field))
                    .build());
            return null;
        }
        return trimmed;
    }

    private boolean isSet(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    private boolean pricedUnderSomeProvider(String modelName, Set<String> providers, PriceLayers layers) {
        return providers.stream()
                .anyMatch(provider -> ModelPriceResolver.resolve(modelName, provider, layers).isPresent());
    }

    private RowKey keyOf(CustomModelPrice price) {
        return RowKey.builder().modelName(price.modelName()).provider(price.provider()).build();
    }

    private String describeProvider(String provider) {
        return provider.isEmpty() ? "any provider" : "'%s'".formatted(provider);
    }
}
