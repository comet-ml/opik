package com.comet.opik.domain.cost;

import com.comet.opik.domain.cost.CustomModelPriceValidator.Code;
import com.comet.opik.domain.cost.CustomModelPriceValidator.Problem;
import com.comet.opik.domain.cost.CustomModelPriceValidator.Result;
import com.comet.opik.domain.cost.CustomModelPriceValidator.ValidRow;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.domain.cost.CustomModelPrice.ANY_PROVIDER;
import static com.comet.opik.domain.cost.CustomModelPrice.SPAN_PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CustomModelPriceValidatorTest {

    private static final String NO_BASE_MESSAGE = "No built-in price for '%s' on %s: rates left unset bill nothing, and"
            + " cached tokens bill at the input price";
    private static final String OUT_OF_RANGE_MESSAGE = "%s per 1M tokens is far from the built-in %s: prices are per"
            + " 1M tokens, not per token";

    private static String customName() {
        return "team-" + RandomStringUtils.secure().nextAlphanumeric(32).toLowerCase(Locale.ROOT);
    }

    private static BigDecimal randomPrice() {
        return BigDecimal.valueOf(RandomUtils.secure().randomInt(1, 10_000), 2);
    }

    private static String perMillion(BigDecimal perToken) {
        return perToken.movePointRight(6).stripTrailingZeros().toPlainString();
    }

    private static ModelPrice builtIn(String model, String provider) {
        return ModelPriceResolver.resolve(model, provider).orElseThrow().price();
    }

    // Keys and values in pairs, kept in order; a value can be null, a number, a string or a nested map.
    private static Map<String, Object> row(Object... keysAndValues) {
        var row = new LinkedHashMap<String, Object>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            row.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return row;
    }

    private static Map<String, Object> target(String model, String provider) {
        return provider == null ? row("model", model) : row("model", model, "provider", provider);
    }

    @SafeVarargs
    private static Result validate(Map<String, Object>... rows) {
        return validate(Arrays.asList(rows));
    }

    private static Result validate(List<?> rows) {
        List<JsonNode> nodes = rows.stream().map(JsonUtils::valueToTree).toList();
        return CustomModelPriceValidator.validate(nodes);
    }

    private static CustomModelPrice fixed(String model, String provider, BigDecimal input, BigDecimal output) {
        return CustomModelPrice.builder()
                .modelName(model)
                .provider(provider)
                .kind(CustomModelPrice.Kind.FIXED)
                .inputPerMillion(input)
                .outputPerMillion(output)
                .build();
    }

    private static CustomModelPrice alias(String model, String provider, String targetModel, String targetProvider) {
        return CustomModelPrice.builder()
                .modelName(model)
                .provider(provider)
                .kind(CustomModelPrice.Kind.ALIAS)
                .aliasModelName(targetModel)
                .aliasProvider(targetProvider)
                .build();
    }

    private static ValidRow valid(CustomModelPrice price) {
        return ValidRow.builder().price(price).build();
    }

    private static Problem problem(Integer row, String field, Code code, String message) {
        return Problem.builder().row(row).field(field).code(code).message(message).build();
    }

    private static Result accepted(List<ValidRow> rows, Problem... warnings) {
        return Result.builder().rows(rows).errors(List.of()).warnings(List.of(warnings)).build();
    }

    private static Result rejected(List<Problem> errors, Problem... warnings) {
        return Result.builder().rows(List.of()).errors(errors).warnings(List.of(warnings)).build();
    }

    private static void assertResult(Result actual, Result expected) {
        assertThat(actual).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(expected);
    }

    @Test
    void validateNormalizesRowsTheWaySpansAreLookedUp() {
        ModelPrice builtIn = builtIn("gpt-5-mini", "openai");
        BigDecimal input = builtIn.inputPrice().movePointRight(6).multiply(BigDecimal.TWO);
        BigDecimal output = builtIn.outputPrice().movePointRight(6).multiply(BigDecimal.TWO);

        var result = validate(row("model", " OpenAI/GPT-5-Mini ", "kind", "fixed", "input_price",
                input.toPlainString(), "output_price", output));

        assertResult(result, accepted(List.of(valid(fixed("gpt-5-mini", "openai", input, output))),
                problem(0, "model", Code.NAME_NORMALIZED, "Saved as 'gpt-5-mini', the form spans are looked up in"),
                problem(0, "provider", Code.NAME_NORMALIZED, "Saved as 'openai', the form spans are looked up in")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Together", "api.together.xyz"})
    void validateStoresAProviderUnderItsPriceFileNameAndSaysSoOnTheProvider(String sentProvider) {
        String model = customName();
        BigDecimal input = randomPrice();

        var result = validate(row("model", model, "provider", sentProvider, "kind", "fixed", "input_price", input));

        assertResult(result, accepted(List.of(valid(fixed(model, "together_ai", input, null))),
                problem(0, "provider", Code.NAME_NORMALIZED, "Saved as 'together_ai', the form spans are looked up in"),
                problem(0, "model", Code.NO_BASE, NO_BASE_MESSAGE.formatted(model, "'together_ai'"))));
    }

    private static Stream<Arguments> namesWithInvisibleEnds() {
        return Stream.of(
                Arguments.of("trailing new line", "gpt-5-mini\n"),
                Arguments.of("leading tab", "\tgpt-5-mini"),
                Arguments.of("trailing non-breaking space", "gpt-5-mini "),
                Arguments.of("leading byte order mark", "﻿gpt-5-mini"),
                Arguments.of("trailing zero-width space", "gpt-5-mini​"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namesWithInvisibleEnds")
    void validateStripsInvisibleCharactersAtTheEndsOfAName(String description, String sentModel) {
        BigDecimal input = builtIn("gpt-5-mini", "openai").inputPrice().movePointRight(6);

        var result = validate(row("model", sentModel, "provider", "openai", "kind", "fixed", "input_price", input));

        assertResult(result, accepted(List.of(valid(fixed("gpt-5-mini", "openai", input, null))),
                problem(0, "model", Code.NAME_NORMALIZED, "Saved as 'gpt-5-mini', the form spans are looked up in")));
    }

    @Test
    void validateKeepsARowWhosePrefixIsAVendorForAnyProvider() {
        String model = customName();
        BigDecimal input = randomPrice();

        var result = validate(row("model", "meta-llama/" + model, "kind", "fixed", "input_price", input));

        assertResult(result, accepted(List.of(valid(fixed(model, ANY_PROVIDER, input, null))),
                problem(0, "model", Code.NAME_NORMALIZED, "Saved as '%s', the form spans are looked up in"
                        .formatted(model)),
                problem(0, "model", Code.NO_BASE, NO_BASE_MESSAGE.formatted(model, "any provider"))));
        var priced = CostService.price("meta-llama/" + model, "openai", Map.of("prompt_tokens", 1_000_000), null,
                PriceLayers.of("org-1", 1, List.of(result.rows().getFirst().price())));
        assertThat(priced).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(new PricedCost(input, CostSource.ORGANIZATION));
    }

    @Test
    void validateNormalizesTheAliasTargetAndSaysSoOnItsFields() {
        String model = customName();

        var result = validate(row("model", model, "kind", "alias", "alias_of", target(" GPT-5-Mini ", "OpenAI")));

        assertResult(result, accepted(List.of(valid(alias(model, ANY_PROVIDER, "gpt-5-mini", "openai"))),
                problem(0, "alias_of.model", Code.NAME_NORMALIZED,
                        "Saved as 'gpt-5-mini', the form spans are looked up in"),
                problem(0, "alias_of.provider", Code.NAME_NORMALIZED,
                        "Saved as 'openai', the form spans are looked up in")));
    }

    @Test
    void validateReportsEveryProblemOfARowAtOnce() {
        var result = validate(row("model", "gpt-5-mini", "provider", "openai", "kind", "fixed", "input_prise", "1",
                "output_price", "NaN", "cache_read_price", "0.00000000001"));

        assertResult(result, rejected(List.of(
                problem(0, "input_prise", Code.UNKNOWN_FIELD, "Unknown field 'input_prise'"),
                problem(0, "output_price", Code.NOT_A_NUMBER, "output_price must be a number"),
                problem(0, "cache_read_price", Code.TOO_MANY_DECIMALS, "cache_read_price has more than 10 decimals"))));
    }

    @Test
    void validateRejectsALongListWithOnlyTheLimitWhateverItsRows() {
        List<Map<String, Object>> rows = IntStream.rangeClosed(0, CustomModelPriceValidator.MAX_ROWS)
                .mapToObj(index -> index % 2 == 0
                        ? row("model", customName(), "kind", "fixed", "input_price", -1)
                        : row("model", customName(), "kind", "alias", "alias_of", target(customName(), null)))
                .toList();

        assertResult(validate(rows), rejected(List.of(problem(null, null, Code.TOO_MANY_ROWS,
                "At most 1000 rows can be saved, got 1001"))));
    }

    @Test
    void validateAcceptsAListAtTheLimit() {
        List<CustomModelPrice> prices = IntStream.range(0, CustomModelPriceValidator.MAX_ROWS)
                .mapToObj(index -> fixed(customName(), "openai", randomPrice(), null))
                .toList();

        var result = validate(prices.stream()
                .map(price -> row("model", price.modelName(), "provider", "openai", "kind", "fixed", "input_price",
                        price.inputPerMillion()))
                .toList());

        assertResult(result, accepted(prices.stream().map(CustomModelPriceValidatorTest::valid).toList(),
                IntStream.range(0, prices.size())
                        .mapToObj(index -> problem(index, "model", Code.NO_BASE,
                                NO_BASE_MESSAGE.formatted(prices.get(index).modelName(), "'openai'")))
                        .toArray(Problem[]::new)));
    }

    @Test
    void validateAcceptsEveryValueAtItsLimit() {
        String model = RandomStringUtils.secure().nextAlphanumeric(CustomModelPriceValidator.MAX_NAME_LENGTH)
                .toLowerCase(Locale.ROOT);
        String note = RandomStringUtils.secure().nextAlphanumeric(CustomModelPriceValidator.MAX_NOTE_LENGTH);
        BigDecimal input = new BigDecimal("0.0000000001");
        BigDecimal output = CustomModelPriceValidator.MAX_PRICE_PER_MILLION;

        var result = validate(row("model", model, "provider", "openai", "kind", "fixed", "input_price",
                input.toPlainString(), "output_price", output.toPlainString(), "note", note));

        assertResult(result, accepted(List.of(ValidRow.builder().price(fixed(model, "openai", input, output))
                .note(note).build()), problem(0, "model", Code.NO_BASE, NO_BASE_MESSAGE.formatted(model, "'openai'"))));
    }

    private static Stream<Arguments> invalidRows() {
        String tooLong = "m".repeat(CustomModelPriceValidator.MAX_NAME_LENGTH + 1);
        return Stream.of(
                Arguments.of(row("provider", "openai", "kind", "fixed", "input_price", 1),
                        "model", Code.MISSING_MODEL, "Every row needs a model"),
                Arguments.of(row("model", " ", "kind", "fixed", "input_price", 1),
                        "model", Code.MISSING_MODEL, "Every row needs a model"),
                Arguments.of(row("model", "openai/", "kind", "fixed", "input_price", 1),
                        "model", Code.MISSING_MODEL, "The model has no name after its provider"),
                Arguments.of(row("model", 5, "kind", "fixed", "input_price", 1),
                        "model", Code.NOT_A_STRING, "model must be a string"),
                Arguments.of(row("model", "gpt-5-mini", "provider", 5, "kind", "fixed", "input_price", 1),
                        "provider", Code.NOT_A_STRING, "provider must be a string"),
                Arguments.of(row("model", "gpt-5-mini", "kind", 5, "input_price", 1),
                        "kind", Code.NOT_A_STRING, "kind must be a string"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "input_price", 1, "note", 5),
                        "note", Code.NOT_A_STRING, "note must be a string"),
                Arguments.of(row("model", tooLong, "kind", "fixed", "input_price", 1),
                        "model", Code.NAME_TOO_LONG, "model is 256 characters, at most 255"),
                Arguments.of(row("model", "gpt-5-mini", "provider", tooLong, "kind", "fixed", "input_price", 1),
                        "provider", Code.NAME_TOO_LONG, "provider is 256 characters, at most 255"),
                Arguments.of(row("model", "gpt\u0007-5", "kind", "fixed", "input_price", 1),
                        "model", Code.CONTROL_CHARACTERS, "model can't contain control or invisible characters"),
                Arguments.of(row("model", "gpt​-5-mini", "kind", "fixed", "input_price", 1),
                        "model", Code.CONTROL_CHARACTERS, "model can't contain control or invisible characters"),
                Arguments.of(row("model", "gpt-5-mini", "provider", "open\u0007ai", "kind", "fixed", "input_price", 1),
                        "provider", Code.CONTROL_CHARACTERS, "provider can't contain control or invisible characters"),
                Arguments.of(row("model", "gpt-5-mini", "input_price", 1),
                        "kind", Code.MISSING_KIND, "Every row needs a kind: fixed or alias"),
                Arguments.of(row("model", "gpt-5-mini", "kind", " ", "input_price", 1),
                        "kind", Code.MISSING_KIND, "Every row needs a kind: fixed or alias"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "discount", "input_price", 1),
                        "kind", Code.UNKNOWN_KIND, "The kind is fixed or alias, got 'discount'"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed"),
                        null, Code.NO_PRICE, "A fixed row needs at least one price"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "input_price", 1,
                        "alias_of", target("gpt-4o", null)),
                        "alias_of", Code.FIELD_NOT_FOR_KIND, "A fixed row can't have alias_of"),
                Arguments.of(row("model", "team-mini", "kind", "alias", "input_price", 1,
                        "alias_of", target("gpt-5-mini", "openai")),
                        "input_price", Code.FIELD_NOT_FOR_KIND,
                        "An alias row prices like its target, so it can't have input_price"),
                Arguments.of(row("model", "team-mini", "kind", "alias"),
                        "alias_of", Code.MISSING_ALIAS_TARGET,
                        "An alias row needs alias_of with the model it prices like"),
                Arguments.of(row("model", "team-mini", "kind", "alias", "alias_of", "gpt-5-mini"),
                        "alias_of", Code.MISSING_ALIAS_TARGET,
                        "An alias row needs alias_of with the model it prices like"),
                Arguments.of(row("model", "team-mini", "kind", "alias", "alias_of", row()),
                        "alias_of.model", Code.MISSING_ALIAS_TARGET, "An alias row needs the model it prices like"),
                Arguments.of(row("model", "team-mini", "kind", "alias", "alias_of", row("model", 5)),
                        "alias_of.model", Code.NOT_A_STRING, "alias_of.model must be a string"),
                Arguments.of(row("model", "team-mini", "kind", "alias", "alias_of", row("model", "gpt-5-mini",
                        "provider", 5)), "alias_of.provider", Code.NOT_A_STRING, "alias_of.provider must be a string"),
                Arguments.of(row("model", "team-mini", "kind", "alias", "alias_of", target(tooLong, null)),
                        "alias_of.model", Code.NAME_TOO_LONG, "alias_of.model is 256 characters, at most 255"),
                Arguments.of(row("model", "team-mini", "kind", "alias", "alias_of", target("gpt-5-mini",
                        "open​ai")), "alias_of.provider", Code.CONTROL_CHARACTERS,
                        "alias_of.provider can't contain control or invisible characters"),
                Arguments.of(row("model", "team-mini", "kind", "alias", "alias_of", row("model", "gpt-5-mini",
                        "deployment", "x")), "alias_of.deployment", Code.UNKNOWN_FIELD,
                        "Unknown field 'alias_of.deployment'"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "input_price", -1),
                        "input_price", Code.NEGATIVE_PRICE, "input_price can't be negative"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "cache_read_price", 0),
                        "cache_read_price", Code.CACHE_PRICE_NOT_POSITIVE,
                        "cache_read_price must be above 0; leave it out to keep the built-in cache price"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "cache_write_price", 0),
                        "cache_write_price", Code.CACHE_PRICE_NOT_POSITIVE,
                        "cache_write_price must be above 0; leave it out to keep the built-in cache price"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "cache_write_price", -1),
                        "cache_write_price", Code.CACHE_PRICE_NOT_POSITIVE,
                        "cache_write_price must be above 0; leave it out to keep the built-in cache price"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "output_price", "100000.01"),
                        "output_price", Code.PRICE_TOO_HIGH, "output_price is over 100000 per 1M tokens"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "input_price", true),
                        "input_price", Code.NOT_A_NUMBER, "input_price must be a number"),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "input_price", 1, "note", "n".repeat(501)),
                        "note", Code.NOTE_TOO_LONG, "The note is 501 characters, at most 500"));
    }

    @ParameterizedTest(name = "{2} on {1}: {0}")
    @MethodSource("invalidRows")
    void validateRejectsAnInvalidRow(Map<String, Object> row, String field, Code code, String message) {
        assertResult(validate(row), rejected(List.of(problem(0, field, code, message))));
    }

    @Test
    void validateRejectsARowThatIsNotAnObject() {
        assertResult(validate(List.of(42)), rejected(List.of(
                problem(0, null, Code.NOT_AN_OBJECT, "Each row must be a JSON object"))));
    }

    @Test
    void validateTreatsANullPriceOnAnAliasRowAndANullAliasOfOnAFixedRowAsLeftOut() {
        String aliasName = customName();
        String fixedName = customName();
        BigDecimal input = randomPrice();

        var result = validate(
                row("model", aliasName, "provider", "openai", "kind", "alias", "input_price", null,
                        "alias_of", target("gpt-5-mini", "openai")),
                row("model", fixedName, "provider", "openai", "kind", "fixed", "input_price", input,
                        "alias_of", null));

        assertResult(result, accepted(
                List.of(valid(alias(aliasName, "openai", "gpt-5-mini", "openai")),
                        valid(fixed(fixedName, "openai", input, null))),
                problem(1, "model", Code.NO_BASE, NO_BASE_MESSAGE.formatted(fixedName, "'openai'"))));
    }

    private static Stream<Arguments> rowsThatAreTheSameOnceNormalized() {
        return Stream.of(
                Arguments.of(row("model", "GPT-5-MINI", "provider", "OpenAI", "kind", "fixed", "input_price", 1)),
                Arguments.of(row("model", "openai/gpt-5-mini", "kind", "fixed", "input_price", 1)));
    }

    @ParameterizedTest
    @MethodSource("rowsThatAreTheSameOnceNormalized")
    void validateRejectsRowsThatAreTheSameOnceNormalized(Map<String, Object> second) {
        var result = validate(row("model", "gpt-5-mini", "provider", "openai", "kind", "fixed", "input_price", 1),
                second);

        assertResult(result, rejected(List.of(problem(1, "model", Code.DUPLICATE_ROW,
                "Row 1 already prices 'gpt-5-mini' on 'openai'"))));
    }

    private static Stream<Arguments> invalidAliasTargets() {
        String first = customName();
        String second = customName();
        return Stream.of(
                Arguments.of("target is an alias on the same provider", List.of(
                        row("model", first, "provider", "openai", "kind", "alias", "alias_of",
                                target(second, "openai")),
                        row("model", second, "provider", "openai", "kind", "alias",
                                "alias_of", target("gpt-5-mini", "openai"))),
                        problem(0, "alias_of", Code.ALIAS_TARGET_IS_ALIAS,
                                "'%s' is an alias too: point this row at a model with a price".formatted(second))),
                Arguments.of("dated target matches an alias of its base name", List.of(
                        row("model", first, "provider", "openai", "kind", "alias",
                                "alias_of", target("gpt-5-mini-2025-08-07", null)),
                        row("model", "gpt-5-mini", "provider", "openai", "kind", "alias",
                                "alias_of", target("gpt-4o", null))),
                        problem(0, "alias_of", Code.ALIAS_TARGET_IS_ALIAS,
                                "'gpt-5-mini' is an alias too: point this row at a model with a price")),
                Arguments.of("any-provider alias reaches an alias under one provider", List.of(
                        row("model", first, "kind", "alias", "alias_of", target("gpt-5-mini", null)),
                        row("model", "gpt-5-mini", "provider", "openai", "kind", "alias",
                                "alias_of", target("gpt-4o", null))),
                        problem(0, "alias_of", Code.ALIAS_TARGET_IS_ALIAS,
                                "'gpt-5-mini' is an alias too: point this row at a model with a price")),
                Arguments.of("target is an alias under a sibling provider", List.of(
                        row("model", first, "provider", "bedrock", "kind", "alias", "alias_of", target(second, null)),
                        row("model", second, "provider", "bedrock_converse", "kind", "alias",
                                "alias_of", target("gpt-5-mini", "openai"))),
                        problem(0, "alias_of", Code.ALIAS_TARGET_IS_ALIAS,
                                "'%s' is an alias too: point this row at a model with a price".formatted(second))),
                Arguments.of("target is the row itself", List.of(
                        row("model", first, "provider", "openai", "kind", "alias", "alias_of", target(first, null))),
                        problem(0, "alias_of", Code.ALIAS_TARGET_IS_ITSELF, "An alias row can't price like itself")),
                Arguments.of("target priced nowhere under the provider", List.of(
                        row("model", first, "provider", "openai", "kind", "alias", "alias_of",
                                target(second, "openai"))),
                        problem(0, "alias_of", Code.ALIAS_TARGET_UNPRICED,
                                "Neither the built-in prices nor these rows price '%s' on 'openai'".formatted(second))),
                Arguments.of("any-provider target priced nowhere", List.of(
                        row("model", first, "kind", "alias", "alias_of", target(second, null))),
                        problem(0, "alias_of", Code.ALIAS_TARGET_UNPRICED,
                                "Neither the built-in prices nor these rows price '%s' on any provider"
                                        .formatted(second))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAliasTargets")
    void validateRejectsAnAliasThatCantPrice(String description, List<Map<String, Object>> rows, Problem error) {
        assertThat(validate(rows).errors()).containsExactly(error);
    }

    @Test
    void validateReportsOnlyTheTargetsOwnErrorWhenAnAliasPointsAtAnInvalidRow() {
        String aliasName = customName();
        String targetName = customName();

        var result = validate(
                row("model", aliasName, "provider", "azure", "kind", "alias", "alias_of", target(targetName, "azure")),
                row("model", targetName, "provider", "azure", "kind", "fixed", "input_price", -1));

        assertResult(result, rejected(List.of(problem(1, "input_price", Code.NEGATIVE_PRICE,
                "input_price can't be negative"))));
    }

    private static Stream<Arguments> validLists() {
        String first = customName();
        String second = customName();
        BigDecimal input = randomPrice();
        String id = UUID.randomUUID().toString();
        String at = Instant.now().toString();
        String user = RandomStringUtils.secure().nextAlphanumeric(16);
        String note = RandomStringUtils.secure().nextAlphanumeric(64);
        BigDecimal gpt5MiniInput = builtIn("gpt-5-mini", "openai").inputPrice().movePointRight(6);
        return Stream.of(
                Arguments.of("alias of a dated spelling of its own name",
                        List.of(row("model", "gpt-5-mini", "provider", "openai", "kind", "alias",
                                "alias_of", target("gpt-5-mini-2025-08-07", null))),
                        List.of(valid(alias("gpt-5-mini", "openai", "gpt-5-mini-2025-08-07", SPAN_PROVIDER))),
                        List.of(problem(0, "model", Code.SHADOWS_BUILTIN,
                                "'gpt-5-mini' has a built-in price, which this alias replaces"))),
                Arguments.of("alias of a fixed row in the same list",
                        List.of(row("model", first, "provider", "azure", "kind", "alias",
                                "alias_of", target(second, "azure")),
                                row("model", second, "provider", "azure", "kind", "fixed", "input_price", input)),
                        List.of(valid(alias(first, "azure", second, "azure")),
                                valid(fixed(second, "azure", input, null))),
                        List.of(problem(1, "model", Code.NO_BASE, NO_BASE_MESSAGE.formatted(second, "'azure'")))),
                Arguments.of("any-provider alias of a fixed row under one provider",
                        List.of(row("model", first, "kind", "alias", "alias_of", target(second, null)),
                                row("model", second, "provider", "llm-gateway.example.com", "kind", "fixed",
                                        "input_price", input)),
                        List.of(valid(alias(first, ANY_PROVIDER, second, SPAN_PROVIDER)),
                                valid(fixed(second, "llm-gateway.example.com", input, null))),
                        List.of(problem(1, "provider", Code.PROVIDER_UNPRICED,
                                "No built-in price is filed under 'llm-gateway.example.com', so only these rows price"
                                        + " its spans"),
                                problem(1, "model", Code.NO_BASE,
                                        NO_BASE_MESSAGE.formatted(second, "'llm-gateway.example.com'")))),
                Arguments.of("any-provider alias whose target names a provider",
                        List.of(row("model", first, "kind", "alias", "alias_of", target("gpt-5-mini", "openai"))),
                        List.of(valid(alias(first, ANY_PROVIDER, "gpt-5-mini", "openai"))),
                        List.of()),
                Arguments.of("alias of a gateway model keeps its vendor prefix",
                        List.of(row("model", first, "provider", "openrouter", "kind", "alias",
                                "alias_of", target("openai/gpt-4o-mini", "openrouter"))),
                        List.of(valid(alias(first, "openrouter", "openai/gpt-4o-mini", "openrouter"))),
                        List.of()),
                Arguments.of("row within range of its built-in price",
                        List.of(row("model", "gpt-5-mini", "provider", "openai", "kind", "fixed",
                                "input_price", gpt5MiniInput)),
                        List.of(valid(fixed("gpt-5-mini", "openai", gpt5MiniInput, null))),
                        List.of()),
                Arguments.of("read-only fields of a listed row",
                        List.of(row("id", id, "created_at", at, "created_by", user, "last_updated_at", at,
                                "last_updated_by", user, "model", "gpt-5-mini", "provider", "openai", "kind", "fixed",
                                "input_price", gpt5MiniInput, "note", note)),
                        List.of(ValidRow.builder().price(fixed("gpt-5-mini", "openai", gpt5MiniInput, null)).note(note)
                                .build()),
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validLists")
    void validateAcceptsAValidList(String description, List<Map<String, Object>> rows, List<ValidRow> expected,
            List<Problem> warnings) {
        assertResult(validate(rows), accepted(expected, warnings.toArray(Problem[]::new)));
    }

    private static Stream<Arguments> rowsWithAWarning() {
        String custom = customName();
        BigDecimal gpt5MiniInput = builtIn("gpt-5-mini", "openai").inputPrice();
        BigDecimal anyProviderGpt5MiniInput = ModelPriceResolver.builtInMatchesUnderEveryProvider("gpt-5-mini")
                .stream()
                .map(match -> match.price().inputPrice())
                .filter(price -> price.signum() > 0)
                .min(BigDecimal::compareTo)
                .orElseThrow();
        return Stream.of(
                Arguments.of(row("model", "gpt-5-mini", "provider", "openai", "kind", "fixed",
                        "input_price", "0.00000025"),
                        problem(0, "input_price", Code.PRICE_OUT_OF_RANGE,
                                OUT_OF_RANGE_MESSAGE.formatted("0.00000025", perMillion(gpt5MiniInput)))),
                Arguments.of(row("model", "gpt-5-mini", "kind", "fixed", "input_price", "0.00000025"),
                        problem(0, "input_price", Code.PRICE_OUT_OF_RANGE,
                                OUT_OF_RANGE_MESSAGE.formatted("0.00000025", perMillion(anyProviderGpt5MiniInput)))),
                Arguments.of(row("model", "gpt-5-mini", "provider", "llm-gateway.example.com", "kind", "alias",
                        "alias_of", target("gpt-5-mini", "azure")),
                        problem(0, "provider", Code.PROVIDER_UNPRICED,
                                "No built-in price is filed under 'llm-gateway.example.com', so only these rows price"
                                        + " its spans")),
                Arguments.of(row("model", "gpt-5-mini", "provider", "openai", "kind", "alias",
                        "alias_of", target("gpt-4o", null)),
                        problem(0, "model", Code.SHADOWS_BUILTIN,
                                "'gpt-5-mini' has a built-in price, which this alias replaces")),
                Arguments.of(row("model", "gpt-5-mini", "kind", "alias", "alias_of", target("gpt-4o", null)),
                        problem(0, "model", Code.SHADOWS_BUILTIN,
                                "'gpt-5-mini' has a built-in price, which this alias replaces")),
                Arguments.of(row("model", custom, "provider", "openai", "kind", "fixed", "input_price", 1),
                        problem(0, "model", Code.NO_BASE, NO_BASE_MESSAGE.formatted(custom, "'openai'"))),
                Arguments.of(row("model", custom, "provider", "openrouter", "kind", "fixed", "input_price", 1),
                        problem(0, "model", Code.NO_BASE, NO_BASE_MESSAGE.formatted(custom, "'openrouter'"))),
                Arguments.of(row("model", custom, "kind", "fixed", "input_price", 1),
                        problem(0, "model", Code.NO_BASE, NO_BASE_MESSAGE.formatted(custom, "any provider"))),
                Arguments.of(row("model", "tts-1", "provider", "openai", "kind", "fixed", "input_price", 1),
                        problem(0, "model", Code.PRICED_PER_UNIT,
                                "'tts-1' is priced per second or per character, so token prices don't apply")),
                Arguments.of(row("model", "tts-1", "kind", "fixed", "input_price", 1),
                        problem(0, "model", Code.PRICED_PER_UNIT,
                                "'tts-1' is priced per second or per character, so token prices don't apply")));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("rowsWithAWarning")
    void validateSavesARowWithAWarning(Map<String, Object> row, Problem warning) {
        var result = validate(row);

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).containsExactly(warning);
    }

    @Test
    void validateWarnsOnEachPriceFieldOutOfRangeOnEitherSide() {
        ModelPrice builtIn = builtIn("claude-sonnet-4-5", "anthropic");
        Function<BigDecimal, BigDecimal> perMillion = perToken -> perToken.movePointRight(6);
        BigDecimal input = perMillion.apply(builtIn.inputPrice()).multiply(BigDecimal.valueOf(100));
        BigDecimal output = builtIn.outputPrice();
        BigDecimal cacheRead = perMillion.apply(builtIn.cacheReadInputTokenPrice()).multiply(BigDecimal.valueOf(100));
        BigDecimal cacheWrite = builtIn.cacheCreationInputTokenPrice();

        var result = validate(row("model", "claude-sonnet-4-5", "provider", "anthropic", "kind", "fixed",
                "input_price", input, "output_price", output.toPlainString(), "cache_read_price", cacheRead,
                "cache_write_price", cacheWrite.toPlainString()));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).containsExactly(
                problem(0, "input_price", Code.PRICE_OUT_OF_RANGE, OUT_OF_RANGE_MESSAGE.formatted(
                        input.stripTrailingZeros().toPlainString(), perMillion(builtIn.inputPrice()))),
                problem(0, "output_price", Code.PRICE_OUT_OF_RANGE, OUT_OF_RANGE_MESSAGE.formatted(
                        output.stripTrailingZeros().toPlainString(), perMillion(builtIn.outputPrice()))),
                problem(0, "cache_read_price", Code.PRICE_OUT_OF_RANGE, OUT_OF_RANGE_MESSAGE.formatted(
                        cacheRead.stripTrailingZeros().toPlainString(),
                        perMillion(builtIn.cacheReadInputTokenPrice()))),
                problem(0, "cache_write_price", Code.PRICE_OUT_OF_RANGE, OUT_OF_RANGE_MESSAGE.formatted(
                        cacheWrite.stripTrailingZeros().toPlainString(),
                        perMillion(builtIn.cacheCreationInputTokenPrice()))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "openai/gpt-4o-mini"})
    void validateFindsAGatewayModelsBuiltInPriceAsSpansDo(String sentModel) {
        ModelPrice builtIn = ModelPriceResolver.resolve("openai/gpt-4o-mini", "openrouter").orElseThrow().price();
        BigDecimal input = builtIn.inputPrice().movePointRight(6);
        BigDecimal output = builtIn.outputPrice().movePointRight(6);

        var result = validate(row("model", sentModel, "provider", "openrouter", "kind", "fixed",
                "input_price", input, "output_price", output));

        List<Problem> warnings = sentModel.equals("gpt-4o-mini")
                ? List.of()
                : List.of(problem(0, "model", Code.NAME_NORMALIZED,
                        "Saved as 'gpt-4o-mini', the form spans are looked up in"));
        assertResult(result, accepted(List.of(valid(fixed("gpt-4o-mini", "openrouter", input, output))),
                warnings.toArray(Problem[]::new)));
    }

    @Test
    void validateComparesAGatewayModelWithTheBuiltInPriceItsSpansGet() {
        String builtInInput = perMillion(ModelPriceResolver.resolve("openai/gpt-4o-mini", "openrouter").orElseThrow()
                .price().inputPrice());

        var result = validate(row("model", "gpt-4o-mini", "provider", "openrouter", "kind", "fixed",
                "input_price", "0.00000015"));

        assertThat(result.warnings()).containsExactly(problem(0, "input_price", Code.PRICE_OUT_OF_RANGE,
                OUT_OF_RANGE_MESSAGE.formatted("0.00000015", builtInInput)));
    }

    // About 4 s of CPU before the alias targets were cached per target and looked up through an index.
    @Test
    void validateChecksAFullListOfAnyProviderAliasesQuickly() {
        List<Map<String, Object>> sharedTarget = IntStream.range(0, CustomModelPriceValidator.MAX_ROWS)
                .mapToObj(index -> row("model", customName(), "kind", "alias", "alias_of", target("gpt-5-mini", null)))
                .toList();
        List<Map<String, Object>> ownTargets = IntStream.range(0, CustomModelPriceValidator.MAX_ROWS)
                .mapToObj(index -> row("model", customName(), "kind", "alias", "alias_of", target(customName(), null)))
                .toList();
        validate(sharedTarget);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(validate(sharedTarget).isValid()).isTrue();
            assertThat(validate(ownTargets).errors()).hasSize(CustomModelPriceValidator.MAX_ROWS);
        });
    }
}
