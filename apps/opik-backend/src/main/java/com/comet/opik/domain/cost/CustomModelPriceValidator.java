package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    public record Problem(@Nullable Integer row, @Nullable String field, @NonNull String code,
            @NonNull String message) {
    }

    public record ValidRow(@NonNull CustomModelPrice price, @Nullable String note) {
    }

    /** The normalized rows to save, only when there is no error. */
    public record Result(@NonNull List<ValidRow> rows, @NonNull List<Problem> errors,
            @NonNull List<Problem> warnings) {

        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    private record ParsedRow(int index, String sentModel, @Nullable String sentProvider, CustomModelPrice price,
            @Nullable String note) {
    }

    private record RowKey(String modelName, String provider) {
    }

    public Result validate(@NonNull List<JsonNode> rows) {
        var errors = new ArrayList<Problem>();
        if (rows.size() > MAX_ROWS) {
            errors.add(new Problem(null, null, "too_many_rows",
                    "At most %d rows can be saved, got %d".formatted(MAX_ROWS, rows.size())));
        }
        var parsedRows = new ArrayList<ParsedRow>();
        Map<RowKey, Integer> firstIndexByKey = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            Optional<ParsedRow> parsed = parse(index, rows.get(index), errors);
            if (parsed.isEmpty()) {
                continue;
            }
            Integer firstIndex = firstIndexByKey.putIfAbsent(keyOf(parsed.get().price()), index);
            if (firstIndex != null) {
                errors.add(new Problem(index, "model", "duplicate_row",
                        "Row %d already prices '%s' on %s".formatted(firstIndex, parsed.get().price().modelName(),
                                describeProvider(parsed.get().price().provider()))));
                continue;
            }
            parsedRows.add(parsed.get());
        }
        checkAliasTargets(parsedRows, errors);
        var warnings = warnings(parsedRows);
        List<ValidRow> validRows = errors.isEmpty()
                ? parsedRows.stream().map(row -> new ValidRow(row.price(), row.note())).toList()
                : List.of();
        return new Result(validRows, List.copyOf(errors), warnings);
    }

    private Optional<ParsedRow> parse(int index, JsonNode node, List<Problem> errors) {
        if (node == null || !node.isObject()) {
            errors.add(new Problem(index, null, "not_an_object", "Each row must be a JSON object"));
            return Optional.empty();
        }
        int errorsBefore = errors.size();
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field) && !READ_ONLY_FIELDS.contains(field)) {
                errors.add(new Problem(index, field, "unknown_field", "Unknown field '%s'".formatted(field)));
            }
        });

        String sentModel = text(index, node, "model", errors);
        String trimmedModel = name(index, "model", sentModel, errors);
        String modelName = trimmedModel == null ? null : normalizeModelName(trimmedModel);
        if (!isSet(node, "model") || (sentModel != null && sentModel.isBlank())) {
            errors.add(new Problem(index, "model", "missing_model", "Every row needs a model"));
        } else if (modelName != null && modelName.isEmpty()) {
            errors.add(new Problem(index, "model", "missing_model", "The model has no name after its provider"));
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
            errors.add(new Problem(index, "note", "note_too_long",
                    "The note is %d characters, at most %d".formatted(note.length(), MAX_NOTE_LENGTH)));
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
        return errors.size() == errorsBefore
                ? Optional.of(new ParsedRow(index, sentModel, sentProvider, priceBuilder.build(), note))
                : Optional.empty();
    }

    private void checkFixed(int index, JsonNode node, List<Problem> errors) {
        if (isSet(node, ALIAS_OF)) {
            errors.add(new Problem(index, ALIAS_OF, "field_not_for_kind", "A fixed row can't have alias_of"));
        }
        if (PRICE_FIELDS.stream().noneMatch(field -> isSet(node, field))) {
            errors.add(new Problem(index, null, "no_price", "A fixed row needs at least one price"));
        }
    }

    private void checkAlias(int index, JsonNode node, CustomModelPrice.CustomModelPriceBuilder priceBuilder,
            List<Problem> errors) {
        PRICE_FIELDS.stream().filter(field -> isSet(node, field)).forEach(field -> errors.add(new Problem(index,
                field, "field_not_for_kind", "An alias row prices like its target, so it can't have %s"
                        .formatted(field))));
        JsonNode aliasOf = node.get(ALIAS_OF);
        if (aliasOf == null || !aliasOf.isObject()) {
            errors.add(new Problem(index, ALIAS_OF, "missing_alias_target",
                    "An alias row needs alias_of with the model it prices like"));
            return;
        }
        aliasOf.fieldNames().forEachRemaining(field -> {
            if (!ALIAS_OF_FIELDS.contains(field)) {
                errors.add(new Problem(index, ALIAS_OF + "." + field, "unknown_field",
                        "Unknown field '%s.%s'".formatted(ALIAS_OF, field)));
            }
        });
        String targetModel = name(index, ALIAS_OF + ".model", text(index, aliasOf, "model", errors), errors);
        if (targetModel == null || normalizeModelName(targetModel).isEmpty()) {
            errors.add(new Problem(index, ALIAS_OF + ".model", "missing_alias_target",
                    "An alias row needs the model it prices like"));
            return;
        }
        String targetProvider = name(index, ALIAS_OF + ".provider", text(index, aliasOf, "provider", errors),
                errors);
        priceBuilder.aliasModelName(normalizeModelName(targetModel))
                .aliasProvider(ProviderRegistry.resolve(targetProvider, targetModel)
                        .orElse(CustomModelPrice.SPAN_PROVIDER));
    }

    private void checkAliasTargets(List<ParsedRow> rows, List<Problem> errors) {
        Map<RowKey, CustomModelPrice> byKey = new HashMap<>();
        rows.forEach(row -> byKey.put(keyOf(row.price()), row.price()));
        var fixedRows = PriceLayers.of("validation", 0, rows.stream()
                .map(ParsedRow::price)
                .filter(price -> price.kind() == CustomModelPrice.Kind.FIXED)
                .toList());
        for (ParsedRow row : rows) {
            if (row.price().kind() == CustomModelPrice.Kind.ALIAS) {
                aliasTargetProblem(row, byKey, fixedRows).ifPresent(errors::add);
            }
        }
    }

    private Optional<Problem> aliasTargetProblem(ParsedRow row, Map<RowKey, CustomModelPrice> byKey,
            PriceLayers fixedRows) {
        CustomModelPrice alias = row.price();
        boolean underSpanProvider = CustomModelPrice.SPAN_PROVIDER.equals(alias.aliasProvider());
        String targetProvider = underSpanProvider ? alias.provider() : alias.aliasProvider();
        var targetKey = new RowKey(alias.aliasModelName(), targetProvider);
        if (targetKey.equals(keyOf(alias))) {
            return Optional.of(new Problem(row.index(), ALIAS_OF, "alias_target_is_itself",
                    "An alias row can't price like itself"));
        }
        CustomModelPrice target = byKey.get(targetKey);
        if (target != null && target.kind() == CustomModelPrice.Kind.ALIAS) {
            return Optional.of(new Problem(row.index(), ALIAS_OF, "alias_target_is_alias",
                    "'%s' is an alias too: point this row at a model with a price".formatted(target.modelName())));
        }
        // An any-provider alias whose target names no provider is looked up under each span's own provider, so
        // whether it prices can only be known per span.
        if (underSpanProvider && CustomModelPrice.ANY_PROVIDER.equals(alias.provider())) {
            return Optional.empty();
        }
        if (ModelPriceResolver.resolve(alias.aliasModelName(), targetProvider, fixedRows).isEmpty()) {
            return Optional.of(new Problem(row.index(), ALIAS_OF, "alias_target_unpriced",
                    "Neither the built-in prices nor these rows price '%s' on %s".formatted(alias.aliasModelName(),
                            describeProvider(targetProvider))));
        }
        return Optional.empty();
    }

    private List<Problem> warnings(List<ParsedRow> rows) {
        var warnings = new ArrayList<Problem>();
        for (ParsedRow row : rows) {
            CustomModelPrice price = row.price();
            if (!price.modelName().equals(row.sentModel()) || !price.provider().equals(
                    Optional.ofNullable(row.sentProvider()).orElse(CustomModelPrice.ANY_PROVIDER))) {
                warnings.add(new Problem(row.index(), "model", "name_normalized",
                        "Saved as '%s' on %s, the form spans are looked up in".formatted(price.modelName(),
                                describeProvider(price.provider()))));
            }
            if (!price.provider().isEmpty() && !ModelPriceTable.isPricedProvider(price.provider())) {
                warnings.add(new Problem(row.index(), "provider", "provider_unpriced",
                        "No built-in price is filed under '%s', so only these rows price its spans"
                                .formatted(price.provider())));
            }
            Optional<ModelPrice> builtIn = price.provider().isEmpty()
                    ? Optional.empty()
                    : ModelPriceResolver.resolve(price.modelName(), price.provider())
                            .map(ModelPriceResolver.PriceMatch::price);
            if (price.kind() == CustomModelPrice.Kind.ALIAS) {
                builtIn.ifPresent(ignored -> warnings.add(new Problem(row.index(), "model", "shadows_builtin",
                        "'%s' has a built-in price, which this alias replaces".formatted(price.modelName()))));
            } else if (!price.provider().isEmpty()) {
                builtIn.ifPresentOrElse(base -> warnings.addAll(fixedRowWarnings(row.index(), price, base)),
                        () -> warnings.add(new Problem(row.index(), "model", "no_base",
                                ("No built-in price for '%s' on %s: rates left unset bill nothing, and cached tokens "
                                        + "bill at the input price").formatted(price.modelName(),
                                                describeProvider(price.provider())))));
            }
        }
        return List.copyOf(warnings);
    }

    private List<Problem> fixedRowWarnings(int index, CustomModelPrice price, ModelPrice base) {
        var warnings = new ArrayList<Problem>();
        if (base.calculator() != SpanCostCalculator.DEFAULT_COST_CALCULATOR && !hasTokenPrices(base)) {
            warnings.add(new Problem(index, "model", "priced_per_unit",
                    "'%s' is priced per second or per character, so token prices don't apply"
                            .formatted(price.modelName())));
        }
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
        return Optional.of(new Problem(index, field, "price_out_of_range",
                "%s per 1M tokens is far from the built-in %s: prices are per 1M tokens, not per token".formatted(
                        perMillion.stripTrailingZeros().toPlainString(),
                        builtInPerMillion.stripTrailingZeros().toPlainString())));
    }

    private @Nullable CustomModelPrice.Kind kind(int index, JsonNode node, List<Problem> errors) {
        String kind = text(index, node, "kind", errors);
        if (StringUtils.isBlank(kind)) {
            errors.add(new Problem(index, "kind", "missing_kind", "Every row needs a kind: fixed or alias"));
            return null;
        }
        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case "fixed" -> CustomModelPrice.Kind.FIXED;
            case "alias" -> CustomModelPrice.Kind.ALIAS;
            default -> {
                errors.add(new Problem(index, "kind", "unknown_kind",
                        "The kind is fixed or alias, got '%s'".formatted(kind)));
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
            errors.add(new Problem(index, field, "not_a_number", "%s must be a number".formatted(field)));
            return null;
        }
        int errorsBefore = errors.size();
        if (price.stripTrailingZeros().scale() > MAX_DECIMALS) {
            errors.add(new Problem(index, field, "too_many_decimals",
                    "%s has more than %d decimals".formatted(field, MAX_DECIMALS)));
        }
        if (price.compareTo(MAX_PRICE_PER_MILLION) > 0) {
            errors.add(new Problem(index, field, "price_too_high",
                    "%s is over %s per 1M tokens".formatted(field, MAX_PRICE_PER_MILLION.toPlainString())));
        }
        boolean cachePrice = CACHE_READ_PRICE.equals(field) || CACHE_WRITE_PRICE.equals(field);
        // The calculator bills cached tokens at the input price when the cache price isn't above 0, so 0 can't mean
        // free here: leave the field out to inherit the built-in cache price.
        if (cachePrice && price.signum() <= 0) {
            errors.add(new Problem(index, field, "cache_price_not_positive",
                    "%s must be above 0; leave it out to keep the built-in cache price".formatted(field)));
        } else if (price.signum() < 0) {
            errors.add(new Problem(index, field, "negative_price", "%s can't be negative".formatted(field)));
        }
        return errors.size() == errorsBefore ? price : null;
    }

    private @Nullable String text(int index, JsonNode node, String field, List<Problem> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(new Problem(index, field, "not_a_string", "%s must be a string".formatted(field)));
            return null;
        }
        return value.asText();
    }

    private @Nullable String name(int index, String field, @Nullable String sent, List<Problem> errors) {
        if (sent == null) {
            return null;
        }
        String trimmed = sent.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            errors.add(new Problem(index, field, "name_too_long",
                    "%s is %d characters, at most %d".formatted(field, trimmed.length(), MAX_NAME_LENGTH)));
            return null;
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            errors.add(new Problem(index, field, "control_characters",
                    "%s can't contain control characters".formatted(field)));
            return null;
        }
        return trimmed;
    }

    private String normalizeModelName(String trimmedModel) {
        return ModelPriceTable.parseModelName(trimmedModel).trim().toLowerCase(Locale.ROOT);
    }

    private boolean isSet(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    private RowKey keyOf(CustomModelPrice price) {
        return new RowKey(price.modelName(), price.provider());
    }

    private String describeProvider(String provider) {
        return provider.isEmpty() ? "any provider" : "'%s'".formatted(provider);
    }

    private boolean hasTokenPrices(ModelPrice price) {
        return price.inputPrice().signum() > 0 || price.outputPrice().signum() > 0
                || price.cacheCreationInputTokenPrice().signum() > 0 || price.cacheReadInputTokenPrice().signum() > 0;
    }
}
