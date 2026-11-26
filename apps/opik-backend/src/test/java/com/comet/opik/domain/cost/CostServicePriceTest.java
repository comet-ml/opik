package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CostServicePriceTest {

    private static final String GATEWAY_HOST = "llm-gateway.example.com";
    private static final JsonNode HALF_A_DOLLAR_METADATA = new ObjectMapper().valueToTree(
            Map.of("cost", Map.of("currency", "USD", "total_cost", 0.5)));

    private static String customName() {
        return "aide-" + RandomStringUtils.secure().nextAlphanumeric(32).toLowerCase(Locale.ROOT);
    }

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
        String local = "custom-llm/local/" + customName();
        String aliasName = "team/aliases/" + customName();
        return Stream.of(
                // 1000*1e-6 + 500*2e-6
                Arguments.of("name with two slashes matches its own spans", local, "ollama:local",
                        usage(1000, 0, 500), layers(fixed(local, "ollama:local", "1.00", "2.00")), "0.002"),
                Arguments.of("alias whose name and target have two slashes", aliasName, "ollama:local",
                        usage(1000, 0, 500), layers(alias(aliasName, "ollama:local", local, "ollama:local"),
                                fixed(local, "ollama:local", "1.00", "2.00")),
                        "0.002"),
                // 1M*1e-6 + 100k*1e-5
                Arguments.of("row typed with a vendor prefix and capitals", "gpt-5-mini", "openai",
                        usage(1_000_000, 0, 100_000), layers(fixed("OpenAI/GPT-5-Mini", "openai", "1.00", "10.00")),
                        "2.00"),
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

    private static Stream<Arguments> spansOutsideARowWithTwoSlashes() {
        String version = customName();
        return Stream.of(
                Arguments.of("another first segment and the same last one", "some-other/" + version, version),
                Arguments.of("the name after the first slash, sent as the whole name", "fine-tune/" + version,
                        version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("spansOutsideARowWithTwoSlashes")
    void priceOfARowWithTwoSlashesSkipsSpansWithAnotherNameAfterTheirFirstSlash(String description,
            String spanModel, String version) {
        var priced = CostService.price(spanModel, "vllm", usage(1000, 0, 500), null,
                layers(fixed("my-org/fine-tune/" + version, "vllm", "1.00", "2.00")));

        assertPriced(priced, new PricedCost(BigDecimal.ZERO, CostSource.NONE));
    }

    private static void assertPriced(PricedCost actual, PricedCost expected) {
        assertThat(actual).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(expected);
    }

    private static Stream<Arguments> rowsNamedLikeAModelPricedPerUnit() {
        // tts-1 bills 1000 characters at 1.5e-5, sora-2 bills 10 seconds at 0.1.
        var characters = Map.of("input_characters", 1000);
        var seconds = Map.of("video_duration_seconds", 10);
        return Stream.of(
                Arguments.of("fixed row, per character", "tts-1", characters,
                        layers(fixed("tts-1", "openai", "1.00", "1.00")), "0.015"),
                Arguments.of("fixed row, per second", "sora-2", seconds,
                        layers(fixed("sora-2", "openai", "1.00", "1.00")), "1.0"),
                Arguments.of("alias to a token-priced model, per character", "tts-1", characters,
                        layers(alias("tts-1", "openai", "gpt-4o", "openai")), "0.015"),
                Arguments.of("alias to a token-priced model, per second", "sora-2", seconds,
                        layers(alias("sora-2", "openai", "gpt-4o", "openai")), "1.0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rowsNamedLikeAModelPricedPerUnit")
    void priceOfAModelPricedPerUnitIgnoresARowsTokenRates(String description, String modelName,
            Map<String, Integer> usage, PriceLayers layers, String expectedCost) {
        var priced = CostService.price(modelName, "openai", usage, null, layers);

        assertPriced(priced, new PricedCost(new BigDecimal(expectedCost), CostSource.BUILT_IN));
    }

    @Test
    void priceOfAnAliasToAModelPricedPerUnitBillsItsUnits() {
        String aliasName = customName();

        var priced = CostService.price(aliasName, "openai", Map.of("input_characters", 1000), null,
                layers(alias(aliasName, "openai", "tts-1", "openai")));

        assertPriced(priced, new PricedCost(new BigDecimal("0.015"), CostSource.ORGANIZATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "azure", "github_copilot"})
    void priceOfAnAnyProviderAliasUsesItsTargetUnderTheSpansProvider(String spanProvider) {
        String aliasName = customName();
        var builtIn = CostService.price("gpt-5-mini", spanProvider, usage(1000, 0, 500), null,
                PriceLayers.BUILT_IN_ONLY);

        var priced = CostService.price(aliasName, spanProvider, usage(1000, 0, 500), null,
                layers(alias(aliasName, CustomModelPrice.ANY_PROVIDER, "gpt-5-mini",
                        CustomModelPrice.SPAN_PROVIDER)));

        assertThat(builtIn.source()).isEqualTo(CostSource.BUILT_IN);
        assertPriced(priced, new PricedCost(builtIn.cost(), CostSource.ORGANIZATION));
    }

    @Test
    void priceOfAnAnyProviderAliasIsNothingUnderAProviderWithoutTheTarget() {
        String aliasName = customName();

        var priced = CostService.price(aliasName, "anthropic", usage(1000, 0, 500), null,
                layers(alias(aliasName, CustomModelPrice.ANY_PROVIDER, "gpt-5-mini",
                        CustomModelPrice.SPAN_PROVIDER)));

        assertPriced(priced, new PricedCost(BigDecimal.ZERO, CostSource.NONE));
    }

    @Test
    void priceOfAModelBilledPerCharacterKeepsItsMetadataCostOverAFixedRow() {
        var priced = CostService.price("tts-1", "openai", usage(1000, 0, 500), HALF_A_DOLLAR_METADATA,
                layers(fixed("tts-1", "openai", "1.00", "1.00")));

        assertThat(priced.cost()).isEqualByComparingTo("0.5");
        assertThat(priced.source()).isEqualTo(CostSource.METADATA);
    }

    @Test
    void priceKeepsAFreeOrganizationPriceOverTheMetadataCost() {
        var priced = CostService.price("gpt-5-mini", "openai", usage(1000, 0, 500), HALF_A_DOLLAR_METADATA,
                layers(fixed("gpt-5-mini", "openai", "0", "0")));

        assertThat(priced.cost()).isEqualByComparingTo("0");
        assertThat(priced.source()).isEqualTo(CostSource.ORGANIZATION);
    }

    private static Stream<Arguments> spansWithoutUsage() {
        return Stream.of(
                Arguments.of("no usage", null),
                Arguments.of("empty usage", Map.of()),
                Arguments.of("zero counts", usage(0, 0, 0)),
                Arguments.of("null and zero counts", nullAndZeroCounts()));
    }

    private static Map<String, Integer> nullAndZeroCounts() {
        var usage = new HashMap<String, Integer>();
        usage.put("prompt_tokens", null);
        usage.put("completion_tokens", 0);
        return usage;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("spansWithoutUsage")
    void priceOfASpanWithoutUsageKeepsItsMetadataCostOverAnOrganizationRow(String description,
            Map<String, Integer> usage) {
        var priced = CostService.price("gpt-5-mini", "openai", usage, HALF_A_DOLLAR_METADATA,
                layers(fixed("gpt-5-mini", "openai", "1.00", "10.00")));

        assertThat(priced.cost()).isEqualByComparingTo("0.5");
        assertThat(priced.source()).isEqualTo(CostSource.METADATA);
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
