package com.comet.opik.domain.cost;

import com.comet.opik.domain.cost.CostService.CostSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CostServicePriceTest {

    private static final String GATEWAY_HOST = "llm-gateway.example.com";
    private static final JsonNode HALF_A_DOLLAR_METADATA = new ObjectMapper().valueToTree(
            Map.of("cost", Map.of("currency", "USD", "total_cost", 0.5)));

    private static CustomModelPrice fixed(String modelName, String provider, String inputPerMillion,
            String outputPerMillion) {
        return CustomModelPrice.builder()
                .modelName(modelName)
                .provider(provider)
                .kind(CustomModelPrice.Kind.FIXED)
                .inputPerMillion(new BigDecimal(inputPerMillion))
                .outputPerMillion(new BigDecimal(outputPerMillion))
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

    private static CustomModelPrice withoutRates(String modelName) {
        return CustomModelPrice.builder()
                .modelName(modelName)
                .provider("openai")
                .kind(CustomModelPrice.Kind.FIXED)
                .build();
    }

    private static PriceLayers layers(CustomModelPrice... rows) {
        return PriceLayers.of("org-1", 12, List.of(rows));
    }

    private static Map<String, Integer> usage(int promptTokens, int cachedTokens, int completionTokens) {
        return Map.of("prompt_tokens", promptTokens,
                "prompt_tokens_details.cached_tokens", cachedTokens,
                "completion_tokens", completionTokens);
    }

    private static Stream<Arguments> organizationPrices() {
        return Stream.of(
                // 1M*1e-6 + 100k*1e-5. Trying one spelling at a time across both lists hits the built-in dated row and
                // gives 1M*2.5e-7 + 100k*2e-6 = 0.45.
                Arguments.of("dated name reaches the organization's base-name row", "gpt-5-mini-2025-08-07",
                        "openai", usage(1_000_000, 0, 100_000),
                        layers(fixed("gpt-5-mini", "openai", "1.00", "10.00")), "2.00"),
                // 500k*1e-6 + 500k*2.5e-8 (built-in cache read) + 100k*1e-5
                Arguments.of("unset cache price inherits the built-in one", "gpt-5-mini", "openai",
                        usage(1_000_000, 500_000, 100_000),
                        layers(fixed("gpt-5-mini", "openai", "1.00", "10.00")), "1.5125"),
                // 1000*2.5e-7 + 500*2e-6, azure's gpt-5-mini read as OpenAI usage
                Arguments.of("alias filed under a gateway host", "gpt-5-mini", GATEWAY_HOST, usage(1000, 0, 500),
                        layers(alias("gpt-5-mini", GATEWAY_HOST, "gpt-5-mini", "azure")), "0.00125"),
                // 800*2.5e-7 + 200*2.5e-8 + 500*2e-6
                Arguments.of("alias under a gateway host with cached tokens", "gpt-5-mini", GATEWAY_HOST,
                        usage(1000, 200, 500),
                        layers(alias("gpt-5-mini", GATEWAY_HOST, "gpt-5-mini", "azure")), "0.001205"),
                // 1000*1e-6 + 500*4e-6
                Arguments.of("alias sees the organization's price for its target", "aide-gpt-5-mini", "azure",
                        usage(1000, 0, 500),
                        layers(alias("aide-gpt-5-mini", "azure", "gpt-5-mini", "azure"),
                                fixed("gpt-5-mini", "azure", "1.00", "4.00")),
                        "0.003"),
                // 300k*2e-6 + 1000*1.2e-5. Keeping gpt-5.4's above-272k tier would give 300k*5e-6 + 1000*2.25e-5.
                Arguments.of("fixed price applies above a long-context tier", "gpt-5.4", "openai",
                        usage(300_000, 0, 1000), layers(fixed("gpt-5.4", "openai", "2.00", "12.00")), "0.612"),
                // 1000*1.1e-6 + 500*4.4e-6, priced from totals as there is no provider
                Arguments.of("span without a provider matches an any-provider row", "aide-gpt-5-mini", null,
                        usage(1000, 0, 500),
                        layers(fixed("aide-gpt-5-mini", CustomModelPrice.ANY_PROVIDER, "1.10", "4.40")), "0.0033"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("organizationPrices")
    void priceUsesTheOrganizationsRows(String description, String modelName, String provider,
            Map<String, Integer> usage, PriceLayers layers, String expectedCost) {
        var priced = CostService.price(modelName, provider, usage, null, layers);

        assertThat(priced.cost()).isEqualByComparingTo(expectedCost);
        assertThat(priced.source()).isEqualTo(CostSource.ORGANIZATION);
    }

    @Test
    void priceWithoutAMatchingRowUsesTheBuiltInList() {
        var priced = CostService.price("gpt-5-mini", "openai", usage(1000, 0, 500), null,
                layers(fixed("gpt-4o", "openai", "1.00", "1.00")));

        assertThat(priced.cost()).isEqualByComparingTo("0.00125");
        assertThat(priced.source()).isEqualTo(CostSource.BUILT_IN);
    }

    @Test
    void priceKeepsAFreeOrganizationPriceOverTheMetadataCost() {
        var priced = CostService.price("gpt-5-mini", "openai", usage(1000, 0, 500), HALF_A_DOLLAR_METADATA,
                layers(fixed("gpt-5-mini", "openai", "0", "0")));

        assertThat(priced.cost()).isEqualByComparingTo("0");
        assertThat(priced.source()).isEqualTo(CostSource.ORGANIZATION);
    }

    @Test
    void priceOfAnAliasToAFreeOrganizationRowIsFree() {
        var priced = CostService.price("aide-gpt-5-mini", "azure", usage(1000, 0, 500), HALF_A_DOLLAR_METADATA,
                layers(alias("aide-gpt-5-mini", "azure", "aide-free", "azure"), fixed("aide-free", "azure", "0", "0")));

        assertThat(priced.cost()).isEqualByComparingTo("0");
        assertThat(priced.source()).isEqualTo(CostSource.ORGANIZATION);
    }

    @Test
    void priceOfAnAliasToARowThatPricesNothingUsesTheSpansBuiltInPrice() {
        var priced = CostService.price("gpt-5-mini", "openai", usage(1000, 0, 500), null,
                layers(alias("gpt-5-mini", "openai", "aide-unlisted", "openai"), withoutRates("aide-unlisted")));

        assertThat(priced.cost()).isEqualByComparingTo("0.00125");
        assertThat(priced.source()).isEqualTo(CostSource.BUILT_IN);
    }

    @Test
    void priceOfARowThatPricesNothingFallsBackToTheMetadataCost() {
        var priced = CostService.price("aide-unlisted", "openai", usage(1000, 0, 500), HALF_A_DOLLAR_METADATA,
                layers(withoutRates("aide-unlisted")));

        assertThat(priced.cost()).isEqualByComparingTo("0.5");
        assertThat(priced.source()).isEqualTo(CostSource.METADATA);
    }

    @Test
    void priceOfAnUnpricedModelFallsBackToTheMetadataCost() {
        var priced = CostService.price("aide-gpt-5-mini", "azure", usage(1000, 0, 500), HALF_A_DOLLAR_METADATA,
                PriceLayers.BUILT_IN_ONLY);

        assertThat(priced.cost()).isEqualByComparingTo("0.5");
        assertThat(priced.source()).isEqualTo(CostSource.METADATA);
    }

    @Test
    void priceOfAnUnpricedModelWithoutMetadataHasNoSource() {
        var priced = CostService.price("aide-gpt-5-mini", "azure", usage(1000, 0, 500), null,
                PriceLayers.BUILT_IN_ONLY);

        assertThat(priced.cost()).isEqualByComparingTo("0");
        assertThat(priced.source()).isEqualTo(CostSource.NONE);
    }
}
