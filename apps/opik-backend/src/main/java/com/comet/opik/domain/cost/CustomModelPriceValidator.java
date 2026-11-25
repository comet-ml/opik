package com.comet.opik.domain.cost;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks an organization's price rows as sent, before any is saved: every problem of every row is reported at once,
 * like a form shows each invalid field. Rows are normalized the way span names are looked up, so a saved row matches
 * the spans it is meant for. Warnings flag rows that save but likely don't do what was meant.
 */
@UtilityClass
public class CustomModelPriceValidator {

    public static final int MAX_ROWS = 1_000;
    static final int MAX_NAME_LENGTH = 255;
    static final int MAX_NOTE_LENGTH = 500;
    static final int MAX_DECIMALS = 10;
    static final BigDecimal MAX_PRICE_PER_MILLION = new BigDecimal("100000");
    static final int MAX_PRICE_TEXT_LENGTH = 40;
    static final int MAX_ECHOED_LENGTH = 64;
    private static final BigDecimal OUT_OF_RANGE_FACTOR = BigDecimal.TEN;

    private static final String MODEL = "model";
    private static final String PROVIDER = "provider";
    private static final String KIND = "kind";
    private static final String NOTE = "note";
    private static final String INPUT_PRICE = "input_price";
    private static final String OUTPUT_PRICE = "output_price";
    private static final String CACHE_READ_PRICE = "cache_read_price";
    private static final String CACHE_WRITE_PRICE = "cache_write_price";
    private static final String ALIAS_OF = "alias_of";
    private static final String ALIAS_OF_MODEL = ALIAS_OF + "." + MODEL;
    private static final String ALIAS_OF_PROVIDER = ALIAS_OF + "." + PROVIDER;
    private static final List<String> PRICE_FIELDS = List.of(INPUT_PRICE, OUTPUT_PRICE, CACHE_READ_PRICE,
            CACHE_WRITE_PRICE);
    private static final Set<String> FIELDS = Set.of(MODEL, PROVIDER, KIND, INPUT_PRICE, OUTPUT_PRICE,
            CACHE_READ_PRICE, CACHE_WRITE_PRICE, ALIAS_OF, NOTE);
    // What a listed row also carries, so rows read from the API can be sent back unchanged.
    private static final Set<String> READ_ONLY_FIELDS = Set.of("id", "created_at", "created_by", "last_updated_at",
            "last_updated_by");
    private static final Set<String> ALIAS_OF_FIELDS = Set.of(MODEL, PROVIDER);

    /** The codes of errors and warnings, which the frontend and the EM tab match on. */
    public enum Code {
        TOO_MANY_ROWS,
        NOT_AN_OBJECT,
        UNKNOWN_FIELD,
        NOT_A_STRING,
        NOT_A_NUMBER,
        MISSING_MODEL,
        NAME_TOO_LONG,
        CONTROL_CHARACTERS,
        MISSING_KIND,
        UNKNOWN_KIND,
        NOTE_TOO_LONG,
        NO_PRICE,
        FIELD_NOT_FOR_KIND,
        MISSING_ALIAS_TARGET,
        TOO_MANY_DECIMALS,
        PRICE_TOO_HIGH,
        CACHE_PRICE_NOT_POSITIVE,
        NEGATIVE_PRICE,
        DUPLICATE_ROW,
        ALIAS_TARGET_IS_ITSELF,
        ALIAS_TARGET_IS_ALIAS,
        ALIAS_TARGET_UNPRICED,
        NAME_NORMALIZED,
        PROVIDER_UNPRICED,
        SHADOWS_BUILTIN,
        NO_BASE,
        PRICED_PER_UNIT,
        PRICE_OUT_OF_RANGE;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Builder(toBuilder = true)
    public record Problem(Integer row, String field, @NonNull Code code, @NonNull String message) {
    }

    @Builder(toBuilder = true)
    public record ValidRow(@NonNull CustomModelPrice price, String note) {
    }

    /** {@code rows} holds the normalized rows to save, and only when there are no errors. */
    @Builder(toBuilder = true)
    public record Result(@NonNull List<ValidRow> rows, @NonNull List<Problem> errors,
            @NonNull List<Problem> warnings) {

        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    @Builder(toBuilder = true)
    private record ParsedRow(int index, @NonNull String sentModel, @NonNull CustomModelPrice price, String note,
            @NonNull List<Problem> normalized) {
    }

    @Builder(toBuilder = true)
    private record RowKey(@NonNull String modelName, @NonNull String provider) {
    }

    public Result validate(@NonNull List<JsonNode> rows) {
        // Nothing else is checked past the limit, so a rejected request costs the same whatever its size.
        if (rows.size() > MAX_ROWS) {
            return Result.builder()
                    .rows(List.of())
                    .errors(List.of(problem(null, null, Code.TOO_MANY_ROWS,
                            "At most %d rows can be saved, got %d".formatted(MAX_ROWS, rows.size()))))
                    .warnings(List.of())
                    .build();
        }
        var errors = new ArrayList<Problem>();
        var parsedRows = new ArrayList<ParsedRow>();
        Set<String> invalidRowNames = new HashSet<>();
        Map<RowKey, Integer> firstIndexByKey = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            Optional<ParsedRow> parsed = parse(index, rows.get(index), invalidRowNames, errors);
            if (parsed.isEmpty()) {
                continue;
            }
            CustomModelPrice price = parsed.get().price();
            Integer firstIndex = firstIndexByKey.putIfAbsent(keyOf(price), index);
            if (firstIndex != null) {
                errors.add(problem(index, MODEL, Code.DUPLICATE_ROW, "Row %d already prices '%s' on %s"
                        .formatted(firstIndex + 1, price.modelName(), describeProvider(price.provider()))));
                continue;
            }
            parsedRows.add(parsed.get());
        }
        checkAliasTargets(parsedRows, invalidRowNames, errors);
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

    private Optional<ParsedRow> parse(int index, JsonNode node, Set<String> invalidRowNames, List<Problem> errors) {
        if (node == null || !node.isObject()) {
            errors.add(problem(index, null, Code.NOT_AN_OBJECT, "Each row must be a JSON object"));
            return Optional.empty();
        }
        int errorsBefore = errors.size();
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field) && !READ_ONLY_FIELDS.contains(field)) {
                errors.add(problem(index, echoed(field), Code.UNKNOWN_FIELD,
                        "Unknown field '%s'".formatted(echoed(field))));
            }
        });

        Optional<String> sentModel = text(index, node, MODEL, MODEL, errors);
        Optional<String> model = sentModel.flatMap(sent -> name(index, MODEL, sent, errors));
        String modelName = model.map(ModelPriceTable::rowModelName).orElse("");
        if (!isSet(node, MODEL) || model.filter(String::isEmpty).isPresent()) {
            errors.add(problem(index, MODEL, Code.MISSING_MODEL, "Every row needs a model"));
        } else if (model.isPresent() && modelName.isEmpty()) {
            errors.add(problem(index, MODEL, Code.MISSING_MODEL, "The model has no name after its provider"));
        }
        Optional<String> sentProvider = text(index, node, PROVIDER, PROVIDER, errors);
        Optional<String> provider = sentProvider.flatMap(sent -> name(index, PROVIDER, sent, errors));
        String rowProvider = rowProvider(provider, model);

        Optional<CustomModelPrice.Kind> kind = kind(index, node, errors);
        Map<String, BigDecimal> prices = new HashMap<>();
        PRICE_FIELDS.forEach(field -> price(index, node, field, errors).ifPresent(price -> prices.put(field, price)));
        Optional<String> note = text(index, node, NOTE, NOTE, errors);
        note.filter(text -> text.length() > MAX_NOTE_LENGTH).ifPresent(text -> errors.add(problem(index, NOTE,
                Code.NOTE_TOO_LONG, "The note is %d characters, at most %d".formatted(text.length(),
                        MAX_NOTE_LENGTH))));

        var priceBuilder = CustomModelPrice.builder()
                .modelName(modelName)
                .provider(rowProvider)
                .kind(kind.orElse(CustomModelPrice.Kind.FIXED))
                .inputPerMillion(prices.get(INPUT_PRICE))
                .outputPerMillion(prices.get(OUTPUT_PRICE))
                .cacheReadPerMillion(prices.get(CACHE_READ_PRICE))
                .cacheWritePerMillion(prices.get(CACHE_WRITE_PRICE));
        var normalized = new ArrayList<Problem>();
        normalized(index, MODEL, sentModel, modelName).ifPresent(normalized::add);
        normalized(index, PROVIDER, sentProvider, rowProvider).ifPresent(normalized::add);
        kind.ifPresent(rowKind -> {
            if (rowKind == CustomModelPrice.Kind.FIXED) {
                checkFixed(index, node, errors);
            } else {
                checkAlias(index, node, priceBuilder, normalized, errors);
            }
        });
        if (errors.size() != errorsBefore) {
            if (!modelName.isEmpty()) {
                invalidRowNames.add(modelName);
            }
            return Optional.empty();
        }
        return Optional.of(ParsedRow.builder()
                .index(index)
                .sentModel(model.orElse(""))
                .price(priceBuilder.build())
                .note(note.orElse(null))
                .normalized(List.copyOf(normalized))
                .build());
    }

    // A blank provider comes from the model's prefix, the way a span with that name and no provider is looked up:
    // "bifrost/gpt-4o" is filed under bifrost, so it doesn't reprice every provider's gpt-4o.
    private String rowProvider(Optional<String> provider, Optional<String> model) {
        return ProviderRegistry.resolve(provider.orElse(null), model.orElse(null))
                .orElse(CustomModelPrice.ANY_PROVIDER);
    }

    private void checkFixed(int index, JsonNode node, List<Problem> errors) {
        if (isSet(node, ALIAS_OF)) {
            errors.add(problem(index, ALIAS_OF, Code.FIELD_NOT_FOR_KIND, "A fixed row can't have alias_of"));
        }
        if (PRICE_FIELDS.stream().noneMatch(field -> isSet(node, field))) {
            errors.add(problem(index, null, Code.NO_PRICE, "A fixed row needs at least one price"));
        }
    }

    // The target keeps its vendor prefix: a gateway model such as openai/gpt-4o-mini on openrouter is priced through
    // its origin provider, which the resolver finds from that prefix.
    private void checkAlias(int index, JsonNode node, CustomModelPrice.CustomModelPriceBuilder priceBuilder,
            List<Problem> normalized, List<Problem> errors) {
        PRICE_FIELDS.stream().filter(field -> isSet(node, field)).forEach(field -> errors.add(problem(index, field,
                Code.FIELD_NOT_FOR_KIND, "An alias row prices like its target, so it can't have %s".formatted(field))));
        JsonNode aliasOf = node.get(ALIAS_OF);
        if (aliasOf == null || !aliasOf.isObject()) {
            errors.add(problem(index, ALIAS_OF, Code.MISSING_ALIAS_TARGET,
                    "An alias row needs alias_of with the model it prices like"));
            return;
        }
        aliasOf.fieldNames().forEachRemaining(field -> {
            if (!ALIAS_OF_FIELDS.contains(field)) {
                errors.add(problem(index, ALIAS_OF + "." + echoed(field), Code.UNKNOWN_FIELD,
                        "Unknown field '%s.%s'".formatted(ALIAS_OF, echoed(field))));
            }
        });
        Optional<String> sentTarget = text(index, aliasOf, MODEL, ALIAS_OF_MODEL, errors);
        Optional<String> target = sentTarget.flatMap(sent -> name(index, ALIAS_OF_MODEL, sent, errors));
        Optional<String> sentTargetProvider = text(index, aliasOf, PROVIDER, ALIAS_OF_PROVIDER, errors);
        Optional<String> targetProvider = sentTargetProvider
                .flatMap(sent -> name(index, ALIAS_OF_PROVIDER, sent, errors));
        if (!isSet(aliasOf, MODEL) || target.map(ModelPriceTable::rowModelName).filter(String::isEmpty).isPresent()) {
            errors.add(problem(index, ALIAS_OF_MODEL, Code.MISSING_ALIAS_TARGET,
                    "An alias row needs the model it prices like"));
            return;
        }
        if (target.isEmpty()) {
            return;
        }
        String targetName = target.get().toLowerCase(Locale.ROOT);
        String aliasProvider = ProviderRegistry.resolve(targetProvider.orElse(null), target.get())
                .orElse(CustomModelPrice.SPAN_PROVIDER);
        priceBuilder.aliasModelName(targetName).aliasProvider(aliasProvider);
        normalized(index, ALIAS_OF_MODEL, sentTarget, targetName).ifPresent(normalized::add);
        normalized(index, ALIAS_OF_PROVIDER, sentTargetProvider, aliasProvider).ifPresent(normalized::add);
    }

    private Optional<Problem> normalized(int index, String field, Optional<String> sent, String saved) {
        if (sent.orElse("").equals(saved)) {
            return Optional.empty();
        }
        String savedAs = switch (field) {
            case PROVIDER -> describeProvider(saved);
            case ALIAS_OF_PROVIDER -> saved.isEmpty() ? "the span's own provider" : "'%s'".formatted(saved);
            default -> "'%s'".formatted(saved);
        };
        return Optional.of(problem(index, field, Code.NAME_NORMALIZED,
                "Saved as %s, the form spans are looked up in".formatted(savedAs)));
    }

    private void checkAliasTargets(List<ParsedRow> rows, Set<String> invalidRowNames, List<Problem> errors) {
        var targets = new AliasTargets(rows.stream().map(ParsedRow::price).toList());
        for (ParsedRow row : rows) {
            if (row.price().kind() == CustomModelPrice.Kind.ALIAS) {
                aliasTargetProblem(row, targets, invalidRowNames).ifPresent(errors::add);
            }
        }
    }

    // A target that names no provider is looked up under the span's own provider: the row's, or each span's for an
    // any-provider row, which is then empty.
    private String targetProviderOf(CustomModelPrice alias) {
        return CustomModelPrice.SPAN_PROVIDER.equals(alias.aliasProvider()) ? alias.provider() : alias.aliasProvider();
    }

    private Optional<Problem> aliasTargetProblem(ParsedRow row, AliasTargets targets, Set<String> invalidRowNames) {
        CustomModelPrice alias = row.price();
        String targetProvider = targetProviderOf(alias);
        String targetName = ModelPriceTable.rowModelName(alias.aliasModelName());
        if (keyOf(alias).equals(RowKey.builder().modelName(targetName).provider(targetProvider).build())) {
            return Optional.of(problem(row.index(), ALIAS_OF, Code.ALIAS_TARGET_IS_ITSELF,
                    "An alias row can't price like itself"));
        }
        // Whether the target prices depends on a row that has errors of its own, which the user fixes first.
        if (invalidRowNames.contains(targetName)) {
            return Optional.empty();
        }
        boolean underEveryProvider = targetProvider.isEmpty();
        // The target is priced with alias rows skipped, so if a span named like the target would match another alias,
        // the two bill differently. Spans match any spelling (a date suffix stripped, ...), not only the exact name.
        // Matching this row itself is fine: both then end at the target's own price.
        Optional<CustomModelPrice> targetAlias = targets.firstRows(alias.aliasModelName(), targetProvider,
                underEveryProvider).stream()
                .filter(target -> target.kind() == CustomModelPrice.Kind.ALIAS && !target.equals(alias))
                .findFirst();
        if (targetAlias.isPresent()) {
            return Optional.of(problem(row.index(), ALIAS_OF, Code.ALIAS_TARGET_IS_ALIAS,
                    "'%s' is an alias too: point this row at a model with a price"
                            .formatted(targetAlias.get().modelName())));
        }
        if (!targets.priced(alias.aliasModelName(), targetProvider, underEveryProvider)) {
            return Optional.of(problem(row.index(), ALIAS_OF, Code.ALIAS_TARGET_UNPRICED,
                    "Neither the built-in prices nor these rows price '%s' on %s".formatted(alias.aliasModelName(),
                            describeProvider(targetProvider))));
        }
        return Optional.empty();
    }

    private List<Problem> warnings(List<ParsedRow> rows) {
        var warnings = new ArrayList<Problem>();
        for (ParsedRow row : rows) {
            CustomModelPrice price = row.price();
            warnings.addAll(row.normalized());
            if (!price.provider().isEmpty() && !ModelPriceTable.isPricedProvider(price.provider())) {
                warnings.add(problem(row.index(), PROVIDER, Code.PROVIDER_UNPRICED,
                        "No built-in price is filed under '%s', so only these rows price its spans"
                                .formatted(price.provider())));
            }
            List<ModelPrice> bases = builtInBases(row);
            if (price.kind() == CustomModelPrice.Kind.ALIAS) {
                warnings.addAll(aliasRowWarnings(row.index(), price, bases));
            } else {
                warnings.addAll(fixedRowWarnings(row.index(), price, bases));
            }
        }
        return List.copyOf(warnings);
    }

    // Spans of a model priced per unit keep their built-in price when the alias target is priced per token, as token
    // rates would bill them for tokens they never log.
    private List<Problem> aliasRowWarnings(int index, CustomModelPrice alias, List<ModelPrice> bases) {
        if (bases.isEmpty()) {
            return List.of();
        }
        if (bases.stream().anyMatch(ModelPrice::isPricedPerUnit) && !isTargetPricedPerUnit(alias)) {
            return List.of(problem(index, MODEL, Code.PRICED_PER_UNIT,
                    "'%s' is priced per second or per character, so the token prices of '%s' don't apply"
                            .formatted(alias.modelName(), alias.aliasModelName())));
        }
        return List.of(problem(index, MODEL, Code.SHADOWS_BUILTIN,
                "'%s' has a built-in price, which this alias replaces".formatted(alias.modelName())));
    }

    // Only a built-in price is billed per unit, as a fixed row sets token rates, so this request's rows don't matter.
    private boolean isTargetPricedPerUnit(CustomModelPrice alias) {
        String targetProvider = targetProviderOf(alias);
        if (targetProvider.isEmpty()) {
            return ModelPriceResolver.builtInMatchesUnderEveryProvider(alias.aliasModelName()).stream()
                    .anyMatch(match -> match.price().isPricedPerUnit());
        }
        return ModelPriceResolver.resolve(alias.aliasModelName(), targetProvider)
                .filter(match -> match.price().isPricedPerUnit())
                .isPresent();
    }

    // The built-in prices the row's spans would get without it. Saving strips a vendor prefix that spans keep: a span
    // named openai/gpt-4o-mini on OpenRouter finds a built-in price through it, so each spelling such a span could
    // have is looked up. An any-provider row applies under every provider that prices the model.
    private List<ModelPrice> builtInBases(ParsedRow row) {
        CustomModelPrice price = row.price();
        Stream<String> names = Stream.of(price.modelName(), row.sentModel()).distinct();
        if (price.provider().isEmpty()) {
            return names
                    .flatMap(name -> ModelPriceResolver.builtInMatchesUnderEveryProvider(name).stream())
                    .map(ModelPriceResolver.PriceMatch::price)
                    .distinct()
                    .toList();
        }
        return Stream.concat(names, ModelPriceTable.prefixedModelNames(price.modelName(), price.provider()).stream())
                .map(name -> ModelPriceResolver.resolve(name, price.provider()))
                .flatMap(Optional::stream)
                .map(ModelPriceResolver.PriceMatch::price)
                .findFirst()
                .stream()
                .toList();
    }

    private List<Problem> fixedRowWarnings(int index, CustomModelPrice price, List<ModelPrice> bases) {
        if (bases.isEmpty()) {
            return List.of(problem(index, MODEL, Code.NO_BASE,
                    "No built-in price for '%s' on %s: rates left unset bill nothing, and cached tokens bill at the input price"
                            .formatted(price.modelName(), describeProvider(price.provider()))));
        }
        if (bases.stream().anyMatch(ModelPrice::isPricedPerUnit)) {
            return List.of(problem(index, MODEL, Code.PRICED_PER_UNIT,
                    "'%s' is priced per second or per character, so token prices don't apply"
                            .formatted(price.modelName())));
        }
        return Stream.of(
                outOfRange(index, INPUT_PRICE, price.inputPerMillion(), bases, ModelPrice::inputPrice),
                outOfRange(index, OUTPUT_PRICE, price.outputPerMillion(), bases, ModelPrice::outputPrice),
                outOfRange(index, CACHE_READ_PRICE, price.cacheReadPerMillion(), bases,
                        ModelPrice::cacheReadInputTokenPrice),
                outOfRange(index, CACHE_WRITE_PRICE, price.cacheWritePerMillion(), bases,
                        ModelPrice::cacheCreationInputTokenPrice))
                .flatMap(Optional::stream)
                .toList();
    }

    // Over 10x or under a tenth of every built-in price the row's spans would otherwise get, which catches a per-token
    // price typed where per 1M is meant. The message names the nearest of them.
    private Optional<Problem> outOfRange(int index, String field, BigDecimal perMillion, List<ModelPrice> bases,
            Function<ModelPrice, BigDecimal> builtInPerToken) {
        if (perMillion == null || perMillion.signum() <= 0) {
            return Optional.empty();
        }
        List<BigDecimal> builtInsPerMillion = bases.stream()
                .map(builtInPerToken)
                .filter(builtIn -> builtIn.signum() > 0)
                .map(builtIn -> builtIn.movePointRight(6))
                .toList();
        if (builtInsPerMillion.isEmpty() || builtInsPerMillion.stream().anyMatch(builtIn -> inRange(perMillion,
                builtIn))) {
            return Optional.empty();
        }
        BigDecimal nearest = builtInsPerMillion.stream()
                .min(Comparator.comparingDouble(builtIn -> Math.abs(Math.log(perMillion.doubleValue())
                        - Math.log(builtIn.doubleValue()))))
                .orElseThrow();
        return Optional.of(problem(index, field, Code.PRICE_OUT_OF_RANGE,
                "%s per 1M tokens is far from the built-in %s: prices are per 1M tokens, not per token"
                        .formatted(perMillion.stripTrailingZeros().toPlainString(),
                                nearest.stripTrailingZeros().toPlainString())));
    }

    private boolean inRange(BigDecimal perMillion, BigDecimal builtInPerMillion) {
        return perMillion.compareTo(builtInPerMillion.multiply(OUT_OF_RANGE_FACTOR)) <= 0
                && perMillion.multiply(OUT_OF_RANGE_FACTOR).compareTo(builtInPerMillion) >= 0;
    }

    private Optional<CustomModelPrice.Kind> kind(int index, JsonNode node, List<Problem> errors) {
        if (!isSet(node, KIND)) {
            errors.add(problem(index, KIND, Code.MISSING_KIND, "Every row needs a kind: fixed or alias"));
            return Optional.empty();
        }
        return text(index, node, KIND, KIND, errors).flatMap(kind -> switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case "fixed" -> Optional.of(CustomModelPrice.Kind.FIXED);
            case "alias" -> Optional.of(CustomModelPrice.Kind.ALIAS);
            case "" -> {
                errors.add(problem(index, KIND, Code.MISSING_KIND, "Every row needs a kind: fixed or alias"));
                yield Optional.empty();
            }
            default -> {
                errors.add(problem(index, KIND, Code.UNKNOWN_KIND,
                        "The kind is fixed or alias, got '%s'".formatted(echoed(kind))));
                yield Optional.empty();
            }
        });
    }

    private Optional<BigDecimal> price(int index, JsonNode node, String field, List<Problem> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        BigDecimal price;
        try {
            if (value.isNumber()) {
                price = value.decimalValue();
            } else if (value.isTextual()) {
                String text = value.asText().trim();
                // BigDecimal parsing time grows faster than the text's length, and a valid price is far shorter.
                if (text.length() > MAX_PRICE_TEXT_LENGTH) {
                    throw new NumberFormatException();
                }
                price = new BigDecimal(text);
            } else {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            errors.add(problem(index, field, Code.NOT_A_NUMBER, "%s must be a number".formatted(field)));
            return Optional.empty();
        }
        int errorsBefore = errors.size();
        if (price.stripTrailingZeros().scale() > MAX_DECIMALS) {
            errors.add(problem(index, field, Code.TOO_MANY_DECIMALS,
                    "%s has more than %d decimals".formatted(field, MAX_DECIMALS)));
        }
        if (price.compareTo(MAX_PRICE_PER_MILLION) > 0) {
            errors.add(problem(index, field, Code.PRICE_TOO_HIGH,
                    "%s is over %s per 1M tokens".formatted(field, MAX_PRICE_PER_MILLION.toPlainString())));
        }
        boolean cachePrice = CACHE_READ_PRICE.equals(field) || CACHE_WRITE_PRICE.equals(field);
        // The calculator bills cached tokens at the input price when the cache price isn't above 0, so 0 can't mean
        // free here: leave the field out to inherit the built-in cache price.
        if (cachePrice && price.signum() <= 0) {
            errors.add(problem(index, field, Code.CACHE_PRICE_NOT_POSITIVE,
                    "%s must be above 0; leave it out to keep the built-in cache price".formatted(field)));
        } else if (price.signum() < 0) {
            errors.add(problem(index, field, Code.NEGATIVE_PRICE, "%s can't be negative".formatted(field)));
        }
        return errors.size() == errorsBefore ? Optional.of(price) : Optional.empty();
    }

    private Optional<String> text(int index, JsonNode node, String key, String field, List<Problem> errors) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            errors.add(problem(index, field, Code.NOT_A_STRING, "%s must be a string".formatted(field)));
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    // Whitespace, control and invisible characters at either end, as a pasted value often has, are stripped and
    // reported by the name_normalized warning. Inside a name they are an error: no span name could match it.
    // String.trim() alone would keep a non-breaking space or a byte order mark.
    private Optional<String> name(int index, String field, String sent, List<Problem> errors) {
        String stripped = stripInvisible(sent);
        if (stripped.length() > MAX_NAME_LENGTH) {
            errors.add(problem(index, field, Code.NAME_TOO_LONG,
                    "%s is %d characters, at most %d".formatted(field, stripped.length(), MAX_NAME_LENGTH)));
            return Optional.empty();
        }
        // Names are saved lower-cased, which can make them longer: 'İ' lower-cases to two characters.
        int savedLength = stripped.toLowerCase(Locale.ROOT).length();
        if (savedLength > MAX_NAME_LENGTH) {
            errors.add(problem(index, field, Code.NAME_TOO_LONG, "%s is %d characters once lower-cased, at most %d"
                    .formatted(field, savedLength, MAX_NAME_LENGTH)));
            return Optional.empty();
        }
        if (stripped.codePoints().anyMatch(CustomModelPriceValidator::isControlOrFormat)) {
            errors.add(problem(index, field, Code.CONTROL_CHARACTERS,
                    "%s can't contain control or invisible characters".formatted(field)));
            return Optional.empty();
        }
        return Optional.of(stripped);
    }

    private String stripInvisible(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isInvisible(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isInvisible(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private boolean isInvisible(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || isControlOrFormat(codePoint);
    }

    private boolean isControlOrFormat(int codePoint) {
        return Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT;
    }

    private boolean isSet(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull();
    }

    private RowKey keyOf(CustomModelPrice price) {
        return RowKey.builder().modelName(price.modelName()).provider(price.provider()).build();
    }

    private String describeProvider(String provider) {
        return provider.isEmpty() ? "any provider" : "'%s'".formatted(provider);
    }

    private String echoed(String sent) {
        return StringUtils.abbreviate(sent, MAX_ECHOED_LENGTH);
    }

    private Problem problem(Integer row, String field, Code code, String message) {
        return Problem.builder().row(row).field(field).code(code).message(message).build();
    }

    /**
     * Answers which rows and prices an alias target reaches, cached per target for one validate call: many aliases
     * share a target, and an any-provider alias whose target names no provider is checked under every provider a span
     * can have, which is every provider in the price file and every provider these rows name.
     */
    private static final class AliasTargets {

        private final PriceLayers rows;
        private final PriceLayers fixedRows;
        // The lookup orders under which a span's first match can differ: those reaching a provider some row is filed
        // under, and the empty order for every provider that reaches none, where only any-provider rows match.
        private final List<List<String>> everyProvidersLookupOrders;
        private final Map<List<Object>, List<CustomModelPrice>> firstRows = new HashMap<>();
        private final Map<List<Object>, Boolean> priced = new HashMap<>();

        AliasTargets(List<CustomModelPrice> prices) {
            List<CustomModelPrice> fixedPrices = prices.stream()
                    .filter(price -> price.kind() == CustomModelPrice.Kind.FIXED)
                    .toList();
            this.rows = PriceLayers.of("validation", 0, prices);
            this.fixedRows = PriceLayers.of("validation", 0, fixedPrices);
            Set<String> rowProviders = prices.stream()
                    .map(CustomModelPrice::provider)
                    .filter(provider -> !provider.isEmpty())
                    .collect(Collectors.toSet());
            // Spans can also come from the providers of this request's rows, which may have no built-in prices.
            Set<List<String>> lookupOrders = Stream.concat(ModelPriceTable.pricedProviders().stream(),
                    rowProviders.stream())
                    .map(ProviderRegistry::priceLookupOrder)
                    .collect(Collectors.toSet());
            this.everyProvidersLookupOrders = Stream.concat(
                    lookupOrders.stream().filter(order -> order.stream().anyMatch(rowProviders::contains)),
                    lookupOrders.stream().anyMatch(order -> order.stream().noneMatch(rowProviders::contains))
                            ? Stream.of(List.<String>of())
                            : Stream.<List<String>>empty())
                    .toList();
        }

        List<CustomModelPrice> firstRows(String target, String provider, boolean underEveryProvider) {
            return firstRows.computeIfAbsent(List.of(target, provider, underEveryProvider), unused -> underEveryProvider
                    ? ModelPriceResolver.firstCustomRows(target, everyProvidersLookupOrders, rows)
                    : ModelPriceResolver.matchingCustomRow(target, provider, rows).stream().toList());
        }

        boolean priced(String target, String provider, boolean underEveryProvider) {
            return priced.computeIfAbsent(List.of(target, provider, underEveryProvider), unused -> underEveryProvider
                    ? !ModelPriceResolver.builtInMatchesUnderEveryProvider(target).isEmpty()
                            || !ModelPriceResolver.firstCustomRows(target, everyProvidersLookupOrders, fixedRows)
                                    .isEmpty()
                    : ModelPriceResolver.resolve(target, provider, fixedRows).isPresent());
        }
    }
}
