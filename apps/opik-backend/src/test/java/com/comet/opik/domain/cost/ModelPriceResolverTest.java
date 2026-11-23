package com.comet.opik.domain.cost;

import com.comet.opik.domain.cost.ModelPriceResolver.NameForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceResolverTest {

    private static Stream<Arguments> matchedNames() {
        return Stream.of(
                Arguments.of("gpt-5-mini-2025-08-07", "openai", "gpt-5-mini-2025-08-07", "openai", NameForm.EXACT),
                Arguments.of("GPT-5-MINI", "openai", "gpt-5-mini", "openai", NameForm.LOWER_CASED),
                Arguments.of("GPT-5.2", "openai", "gpt-5.2", "openai", NameForm.LOWER_CASED),
                Arguments.of("claude-sonnet-4.5", "anthropic", "claude-sonnet-4-5", "anthropic", NameForm.NORMALIZED),
                Arguments.of("gpt-5.2-2025-12-17", "openai", "gpt-5.2", "openai", NameForm.DATE_STRIPPED),
                Arguments.of("anthropic.claude-opus-4-6-v1:0", "bedrock", "anthropic.claude-opus-4-6-v1",
                        "bedrock_converse", NameForm.VERSION_STRIPPED),
                Arguments.of("perplexity/sonar", "openrouter", "sonar", "perplexity", NameForm.EXACT),
                Arguments.of("openai/gpt-5-mini", null, "gpt-5-mini", "openai", NameForm.EXACT));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("matchedNames")
    void resolveReportsMatchedRowProviderAndNameForm(String modelName, String provider, String rowName,
            String rowProvider, NameForm nameForm) {
        var expected = ModelPriceResolver.PriceMatch.builder()
                .price(ModelPriceTable.get(rowName, rowProvider))
                .provider(rowProvider)
                .nameForm(nameForm)
                .build();

        assertThat(ModelPriceResolver.resolve(modelName, provider)).contains(expected);
    }

    private static Stream<Arguments> unmatchedNames() {
        return Stream.of(
                Arguments.of(null, "openai"),
                Arguments.of("", "openai"),
                Arguments.of("  ", "openai"),
                Arguments.of("gpt-5-mini", null),
                Arguments.of("gpt-5-mini", ""),
                Arguments.of("aide-gpt-5-mini", "azure"),
                Arguments.of("gpt-5-mini", "llm-gateway.example.com"));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("unmatchedNames")
    void resolveWhenNothingMatchesReturnsEmpty(String modelName, String provider) {
        assertThat(ModelPriceResolver.resolve(modelName, provider)).isEmpty();
    }

    private static final String GATEWAY_HOST = "llm-gateway.example.com";

    private static CustomModelPrice fixed(String modelName, String provider, String inputPerMillion) {
        return CustomModelPrice.builder()
                .modelName(modelName)
                .provider(provider)
                .kind(CustomModelPrice.Kind.FIXED)
                .inputPerMillion(new BigDecimal(inputPerMillion))
                .build();
    }

    private static CustomModelPrice alias(String modelName, String provider, String targetModel,
            String targetProvider) {
        return CustomModelPrice.builder()
                .modelName(modelName)
                .provider(provider)
                .kind(CustomModelPrice.Kind.ALIAS)
                .aliasModelName(targetModel)
                .aliasProvider(targetProvider)
                .build();
    }

    private static PriceLayers layers(CustomModelPrice... rows) {
        return PriceLayers.of("org-1", 12, List.of(rows));
    }

    @Test
    void resolveTriesEveryOrganizationSpellingBeforeTheBuiltInList() {
        var row = fixed("gpt-5-mini", "openai", "1.00");

        var match = ModelPriceResolver.resolve("gpt-5-mini-2025-08-07", "openai", layers(row)).orElseThrow();

        assertThat(match.customRow()).isEqualTo(row);
        assertThat(match.nameForm()).isEqualTo(NameForm.DATE_STRIPPED);
        assertThat(match.price().inputPrice()).isEqualByComparingTo("0.000001");
        assertThat(match.price().cacheReadInputTokenPrice()).isEqualByComparingTo("2.5E-8");
    }

    @Test
    void resolvePrefersAProviderRowOverAnAnyProviderRow() {
        var providerRow = fixed("gpt-5-mini", "openai", "1.00");
        var anyProviderRow = fixed("gpt-5-mini", CustomModelPrice.ANY_PROVIDER, "9.00");

        var match = ModelPriceResolver.resolve("gpt-5-mini", "openai", layers(anyProviderRow, providerRow));

        assertThat(match).map(ModelPriceResolver.PriceMatch::customRow).contains(providerRow);
    }

    @Test
    void resolveWithoutAProviderMatchesAnAnyProviderRow() {
        var row = fixed("aide-gpt-5-mini", CustomModelPrice.ANY_PROVIDER, "1.10");

        var match = ModelPriceResolver.resolve("aide-gpt-5-mini", null, layers(row));

        assertThat(match).map(ModelPriceResolver.PriceMatch::customRow).contains(row);
        assertThat(ModelPriceResolver.resolve("aide-gpt-5-mini", null)).isEmpty();
    }

    @Test
    void resolveMatchesOrganizationRowsIgnoringLetterCase() {
        var row = fixed("gpt-5-mini", "openai", "1.00");

        var match = ModelPriceResolver.resolve("GPT-5-MINI", "OpenAI", layers(row));

        assertThat(match).map(ModelPriceResolver.PriceMatch::customRow).contains(row);
    }

    @Test
    void resolveAliasFiledUnderAGatewayHostPricesLikeItsTarget() {
        var row = alias("gpt-5-mini", GATEWAY_HOST, "gpt-5-mini", "azure");

        var match = ModelPriceResolver.resolve("gpt-5-mini", GATEWAY_HOST, layers(row)).orElseThrow();

        assertThat(match.price().inputPrice()).isEqualByComparingTo("2.5E-7");
        assertThat(match.price().outputPrice()).isEqualByComparingTo("0.000002");
        assertThat(match.price().cacheReadInputTokenPrice()).isEqualByComparingTo("2.5E-8");
        assertThat(match.provider()).isEqualTo(GATEWAY_HOST);
        assertThat(match.customRow()).isEqualTo(row);
    }

    @Test
    void resolveAliasSeesTheOrganizationsPriceForItsTarget() {
        var aliasRow = alias("aide-gpt-5-mini", "azure", "gpt-5-mini", "azure");
        var targetRow = fixed("gpt-5-mini", "azure", "1.00");

        var match = ModelPriceResolver.resolve("aide-gpt-5-mini", "azure", layers(aliasRow, targetRow))
                .orElseThrow();

        assertThat(match.customRow()).isEqualTo(aliasRow);
        assertThat(match.price().inputPrice()).isEqualByComparingTo("0.000001");
    }

    @Test
    void resolveAliasNeverFollowsASecondAlias() {
        var first = alias("aide-a", "openai", "aide-b", "openai");
        var second = alias("aide-b", "openai", "gpt-5-mini", "openai");

        assertThat(ModelPriceResolver.resolve("aide-a", "openai", layers(first, second))).isEmpty();
    }

    @Test
    void resolveAliasWithAnUnpricedTargetFallsBackToTheBuiltInRow() {
        var row = alias("gpt-5-mini", "openai", "no-such-model", "openai");

        var match = ModelPriceResolver.resolve("gpt-5-mini", "openai", layers(row)).orElseThrow();

        assertThat(match.customRow()).isNull();
        assertThat(match.price()).isSameAs(ModelPriceTable.get("gpt-5-mini", "openai"));
    }

    @Test
    void resolveAliasToARowThatPricesNothingFallsBackToTheBuiltInRow() {
        var aliasRow = alias("gpt-5-mini", "openai", "aide-unlisted", "openai");
        var targetRow = CustomModelPrice.builder()
                .modelName("aide-unlisted")
                .provider("openai")
                .kind(CustomModelPrice.Kind.FIXED)
                .build();

        var match = ModelPriceResolver.resolve("gpt-5-mini", "openai", layers(aliasRow, targetRow)).orElseThrow();

        assertThat(match.customRow()).isNull();
        assertThat(match.price()).isSameAs(ModelPriceTable.get("gpt-5-mini", "openai"));
    }
}
