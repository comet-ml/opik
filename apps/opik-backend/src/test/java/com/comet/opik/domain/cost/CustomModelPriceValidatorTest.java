package com.comet.opik.domain.cost;

import com.comet.opik.domain.cost.CustomModelPriceValidator.Problem;
import com.comet.opik.domain.cost.CustomModelPriceValidator.ValidRow;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class CustomModelPriceValidatorTest {

    private static List<JsonNode> rows(String json) {
        var rows = new ArrayList<JsonNode>();
        JsonUtils.getJsonNodeFromString(json).forEach(rows::add);
        return rows;
    }

    private static List<String> codes(List<Problem> problems) {
        return problems.stream().map(Problem::code).toList();
    }

    @Test
    void validateNormalizesRowsTheWaySpansAreLookedUp() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": " OpenAI/GPT-5-Mini ", "kind": "fixed", "input_price": "1.00", "output_price": 10}]
                """));

        assertThat(result.isValid()).isTrue();
        CustomModelPrice price = result.rows().getFirst().price();
        assertThat(price.modelName()).isEqualTo("gpt-5-mini");
        assertThat(price.provider()).isEqualTo("openai");
        assertThat(price.inputPerMillion()).isEqualByComparingTo("1.00");
        assertThat(price.outputPerMillion()).isEqualByComparingTo("10");
        assertThat(price.cacheReadPerMillion()).isNull();
        assertThat(codes(result.warnings())).containsExactly("name_normalized");
    }

    @Test
    void validateStoresProvidersUnderTheirPriceFileName() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "llama-a", "provider": "Together", "kind": "fixed", "input_price": 1},
                 {"model": "llama-b", "provider": "api.together.xyz", "kind": "fixed", "input_price": 1}]
                """));

        assertThat(result.rows()).extracting(row -> row.price().provider())
                .containsExactly("together_ai", "together_ai");
    }

    @Test
    void validateReportsEveryProblemOfARowAtOnce() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_prise": "1",
                  "output_price": "NaN", "cache_read_price": "0.00000000001"}]
                """));

        assertThat(result.isValid()).isFalse();
        assertThat(result.rows()).isEmpty();
        assertThat(codes(result.errors()))
                .containsExactlyInAnyOrder("unknown_field", "not_a_number", "too_many_decimals");
    }

    @Test
    void validateRejectsMoreThanTheMaximumRows() {
        String json = IntStream.rangeClosed(0, CustomModelPriceValidator.MAX_ROWS)
                .mapToObj("{\"model\": \"team-%d\", \"kind\": \"fixed\", \"input_price\": 1}"::formatted)
                .reduce((first, second) -> first + "," + second)
                .map("[%s]"::formatted)
                .orElseThrow();

        var result = CustomModelPriceValidator.validate(rows(json));

        assertThat(codes(result.errors())).containsExactly("too_many_rows");
    }

    private static Stream<Arguments> invalidRows() {
        return Stream.of(
                Arguments.of("{\"provider\": \"openai\", \"kind\": \"fixed\", \"input_price\": 1}",
                        "model", "missing_model", "Every row needs a model"),
                Arguments.of("{\"model\": \"openai/\", \"kind\": \"fixed\", \"input_price\": 1}",
                        "model", "missing_model", "The model has no name after its provider"),
                Arguments.of("{\"model\": 5, \"kind\": \"fixed\", \"input_price\": 1}", "model", "not_a_string",
                        "model must be a string"),
                Arguments.of("{\"model\": \"%s\", \"kind\": \"fixed\", \"input_price\": 1}".formatted("m".repeat(256)),
                        "model", "name_too_long", "model is 256 characters, at most 255"),
                Arguments.of("{\"model\": \"gpt\\u0007-5\", \"kind\": \"fixed\", \"input_price\": 1}",
                        "model", "control_characters", "model can't contain control characters"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"input_price\": 1}", "kind", "missing_kind",
                        "Every row needs a kind: fixed or alias"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"discount\", \"input_price\": 1}",
                        "kind", "unknown_kind", "The kind is fixed or alias, got 'discount'"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"fixed\"}", null, "no_price",
                        "A fixed row needs at least one price"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"fixed\", \"input_price\": 1,"
                        + " \"alias_of\": {\"model\": \"gpt-4o\"}}", "alias_of", "field_not_for_kind",
                        "A fixed row can't have alias_of"),
                Arguments.of("{\"model\": \"team-mini\", \"kind\": \"alias\", \"input_price\": 1,"
                        + " \"alias_of\": {\"model\": \"gpt-5-mini\", \"provider\": \"openai\"}}",
                        "input_price", "field_not_for_kind",
                        "An alias row prices like its target, so it can't have input_price"),
                Arguments.of("{\"model\": \"team-mini\", \"kind\": \"alias\"}", "alias_of", "missing_alias_target",
                        "An alias row needs alias_of with the model it prices like"),
                Arguments.of("{\"model\": \"team-mini\", \"kind\": \"alias\", \"alias_of\": {\"model\": \"gpt-5-mini\","
                        + " \"deployment\": \"x\"}}", "alias_of.deployment", "unknown_field",
                        "Unknown field 'alias_of.deployment'"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"fixed\", \"input_price\": -1}",
                        "input_price", "negative_price", "input_price can't be negative"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"fixed\", \"cache_read_price\": 0}",
                        "cache_read_price", "cache_price_not_positive",
                        "cache_read_price must be above 0; leave it out to keep the built-in cache price"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"fixed\", \"output_price\": \"100000.01\"}",
                        "output_price", "price_too_high", "output_price is over 100000 per 1M tokens"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"fixed\", \"input_price\": true}",
                        "input_price", "not_a_number", "input_price must be a number"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"fixed\", \"input_price\": 1, \"note\": \"%s\"}"
                        .formatted("n".repeat(501)), "note", "note_too_long",
                        "The note is 501 characters, at most 500"));
    }

    @ParameterizedTest(name = "{2} on {1}")
    @MethodSource("invalidRows")
    void validateRejectsAnInvalidRow(String row, String field, String code, String message) {
        var result = CustomModelPriceValidator.validate(rows("[" + row + "]"));

        assertThat(result.errors()).containsExactly(new Problem(0, field, code, message));
    }

    @Test
    void validateTreatsANullPriceOnAnAliasRowAsLeftOut() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "team-mini", "provider": "openai", "kind": "alias", "input_price": null,
                  "alias_of": {"model": "gpt-5-mini", "provider": "openai"}}]
                """));

        assertThat(result.isValid()).isTrue();
        assertThat(result.rows().getFirst().price().inputPerMillion()).isNull();
    }

    @Test
    void validateRejectsARowThatIsNotAnObject() {
        assertThat(codes(CustomModelPriceValidator.validate(rows("[42]")).errors()))
                .containsExactly("not_an_object");
    }

    @Test
    void validateRejectsRowsThatAreTheSameOnceNormalized() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": 1},
                 {"model": "GPT-5-MINI", "provider": "OpenAI", "kind": "fixed", "input_price": 2}]
                """));

        assertThat(result.errors()).extracting(Problem::row, Problem::code)
                .containsExactly(tuple(1, "duplicate_row"));
    }

    private static Stream<Arguments> invalidAliasTargets() {
        return Stream.of(
                Arguments.of("""
                        [{"model": "team-a", "provider": "openai", "kind": "alias",
                          "alias_of": {"model": "team-b", "provider": "openai"}},
                         {"model": "team-b", "provider": "openai", "kind": "alias",
                          "alias_of": {"model": "gpt-5-mini", "provider": "openai"}}]
                        """, "alias_target_is_alias"),
                Arguments.of("""
                        [{"model": "team-a", "provider": "openai", "kind": "alias", "alias_of": {"model": "team-a"}}]
                        """, "alias_target_is_itself"),
                Arguments.of("""
                        [{"model": "team-a", "provider": "openai", "kind": "alias",
                          "alias_of": {"model": "no-such-model", "provider": "openai"}}]
                        """, "alias_target_unpriced"),
                Arguments.of("""
                        [{"model": "team-a", "kind": "alias", "alias_of": {"model": "no-such-model"}}]
                        """, "alias_target_unpriced"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidAliasTargets")
    void validateRejectsAnAliasThatCantPrice(String json, String code) {
        var result = CustomModelPriceValidator.validate(rows(json));

        assertThat(result.errors()).extracting(Problem::row, Problem::code)
                .containsExactly(tuple(0, code));
    }

    @Test
    void validateAcceptsAnAliasOfAFixedRowInTheSameList() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "team-a", "provider": "azure", "kind": "alias",
                  "alias_of": {"model": "team-base", "provider": "azure"}},
                 {"model": "team-base", "provider": "azure", "kind": "fixed", "input_price": 1}]
                """));

        assertThat(result.isValid()).isTrue();
        assertThat(result.rows().getFirst().price().aliasModelName()).isEqualTo("team-base");
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "azure", "github_copilot"})
    void validateAcceptsAnAnyProviderAliasThatPricesUnderASpansProvider(String spanProvider) {
        assertThat(ModelPriceResolver.resolve("team-mini", spanProvider, anyProviderAliasOfGpt5Mini())).isPresent();
    }

    @Test
    void validateAcceptsAnAnyProviderAliasThatPricesNothingUnderAProviderWithoutTheTarget() {
        assertThat(ModelPriceResolver.resolve("team-mini", "anthropic", anyProviderAliasOfGpt5Mini())).isEmpty();
    }

    private static PriceLayers anyProviderAliasOfGpt5Mini() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "team-mini", "kind": "alias", "alias_of": {"model": "gpt-5-mini"}}]
                """));

        assertThat(result.isValid()).isTrue();
        return PriceLayers.of("org-1", 1, result.rows().stream().map(ValidRow::price).toList());
    }

    @Test
    void validateAcceptsAnAnyProviderAliasOfAFixedRowUnderOneProvider() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "team-a", "kind": "alias", "alias_of": {"model": "team-base"}},
                 {"model": "team-base", "provider": "llm-gateway.example.com", "kind": "fixed", "input_price": 1}]
                """));

        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateAcceptsTheReadOnlyFieldsOfAListedRow() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"id": "0193d0a2-0000-7000-8000-000000000000", "created_at": "2026-11-02T10:00:00Z",
                  "created_by": "admin", "last_updated_at": "2026-11-02T10:00:00Z", "last_updated_by": "admin",
                  "model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "1.00",
                  "note": "Contract 2026-Q3"}]
                """));

        assertThat(result.isValid()).isTrue();
        assertThat(result.rows().getFirst().note()).isEqualTo("Contract 2026-Q3");
    }

    private static Stream<Arguments> rowsWithAWarning() {
        String builtInInput = ModelPriceResolver.resolve("gpt-5-mini", "openai").orElseThrow().price().inputPrice()
                .movePointRight(6).stripTrailingZeros().toPlainString();
        return Stream.of(
                Arguments.of("{\"model\": \"gpt-5-mini\", \"provider\": \"openai\", \"kind\": \"fixed\","
                        + " \"input_price\": \"0.00000025\"}", "input_price", "price_out_of_range",
                        "0.00000025 per 1M tokens is far from the built-in %s: prices are per 1M tokens, not per token"
                                .formatted(builtInInput)),
                Arguments.of(
                        "{\"model\": \"gpt-5-mini\", \"provider\": \"llm-gateway.example.com\", \"kind\": \"alias\","
                                + " \"alias_of\": {\"model\": \"gpt-5-mini\", \"provider\": \"azure\"}}",
                        "provider", "provider_unpriced",
                        "No built-in price is filed under 'llm-gateway.example.com', so only these rows price its spans"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"provider\": \"openai\", \"kind\": \"alias\","
                        + " \"alias_of\": {\"model\": \"gpt-4o\"}}", "model", "shadows_builtin",
                        "'gpt-5-mini' has a built-in price, which this alias replaces"),
                Arguments.of("{\"model\": \"gpt-5-mini\", \"kind\": \"alias\", \"alias_of\": {\"model\": \"gpt-4o\"}}",
                        "model", "shadows_builtin", "'gpt-5-mini' has a built-in price, which this alias replaces"),
                Arguments.of("{\"model\": \"team-mini\", \"provider\": \"openai\", \"kind\": \"fixed\","
                        + " \"input_price\": 1}", "model", "no_base",
                        "No built-in price for 'team-mini' on 'openai': rates left unset bill nothing, and cached tokens"
                                + " bill at the input price"),
                Arguments.of("{\"model\": \"team-mini\", \"provider\": \"openrouter\", \"kind\": \"fixed\","
                        + " \"input_price\": 1}", "model", "no_base",
                        "No built-in price for 'team-mini' on 'openrouter': rates left unset bill nothing, and cached"
                                + " tokens bill at the input price"),
                Arguments.of(
                        "{\"model\": \"tts-1\", \"provider\": \"openai\", \"kind\": \"fixed\", \"input_price\": 1}",
                        "model", "priced_per_unit",
                        "'tts-1' is priced per second or per character, so token prices don't apply"));
    }

    @ParameterizedTest(name = "{2} on {0}")
    @MethodSource("rowsWithAWarning")
    void validateSavesARowWithAWarning(String row, String field, String code, String message) {
        var result = CustomModelPriceValidator.validate(rows("[" + row + "]"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).containsExactly(new Problem(0, field, code, message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o-mini", "openai/gpt-4o-mini"})
    void validateFindsAGatewayModelsBuiltInPriceAsSpansDo(String sentModel) {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "%s", "provider": "openrouter", "kind": "fixed", "input_price": "0.15",
                  "output_price": "0.60"}]
                """.formatted(sentModel)));

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).extracting(Problem::code).doesNotContain("no_base");
    }

    @Test
    void validateComparesAGatewayModelWithTheBuiltInPriceItsSpansGet() {
        String builtInInput = ModelPriceResolver.resolve("openai/gpt-4o-mini", "openrouter").orElseThrow().price()
                .inputPrice().movePointRight(6).stripTrailingZeros().toPlainString();

        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "gpt-4o-mini", "provider": "openrouter", "kind": "fixed", "input_price": "0.00000015"}]
                """));

        assertThat(result.warnings()).containsExactly(new Problem(0, "input_price", "price_out_of_range",
                "0.00000015 per 1M tokens is far from the built-in %s: prices are per 1M tokens, not per token"
                        .formatted(builtInInput)));
    }

    @Test
    void validateGivesNoWarningForARowWithinRange() {
        var result = CustomModelPriceValidator.validate(rows("""
                [{"model": "gpt-5-mini", "provider": "openai", "kind": "fixed", "input_price": "1.00",
                  "output_price": "10.00"}]
                """));

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }
}
