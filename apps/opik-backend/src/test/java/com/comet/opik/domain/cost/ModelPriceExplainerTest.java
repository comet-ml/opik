package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPriceResolution;
import com.comet.opik.api.modelprice.CustomModelPriceResolveRequest;
import com.comet.opik.api.modelprice.CustomModelPriceRow;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceExplainerTest {

    private static final String GATEWAY_HOST = "llm-gateway.example.com";
    private static final String ORGANIZATION_ID = RandomStringUtils.secure().nextAlphanumeric(10);
    private static final long LIST_VERSION = RandomUtils.secure().randomLong(1, 1_000);

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

    private static PriceLayers layers(CustomModelPrice... rows) {
        return PriceLayers.of(ORGANIZATION_ID, LIST_VERSION, List.of(rows));
    }

    private static CustomModelPriceResolveRequest request(String model, String provider, int promptTokens,
            int cachedTokens, int completionTokens) {
        return CustomModelPriceResolveRequest.builder()
                .model(model)
                .provider(provider)
                .usage(Map.of(
                        "prompt_tokens", promptTokens,
                        "prompt_tokens_details.cached_tokens", cachedTokens,
                        "completion_tokens", completionTokens))
                .build();
    }

    private static CustomModelPriceResolution.Line line(String name, String ratePerMillion, CostSource source) {
        return CustomModelPriceResolution.Line.builder().name(name).ratePerMillion(ratePerMillion).source(source)
                .build();
    }

    @Test
    void fixedRowNamesItselfAndTheRatesItSets() {
        var row = fixed("gpt-5-mini", "openai", "1.00", "10.00");

        var resolution = ModelPriceExplainer.explain(request("gpt-5-mini", "openai", 1_000_000, 500_000, 100_000),
                layers(row));

        // 500k*1e-6 + 500k*2.5e-8 (built-in cache read) + 100k*1e-5
        assertThat(resolution).isEqualTo(CustomModelPriceResolution.builder()
                .model("gpt-5-mini")
                .provider("openai")
                .cost("1.5125")
                .costSource(CostSource.ORGANIZATION)
                .listVersion(LIST_VERSION)
                .match(CustomModelPriceResolution.Match.builder()
                        .provider("openai")
                        .nameForm("exact")
                        .row(CustomModelPriceRow.builder()
                                .model("gpt-5-mini")
                                .provider("openai")
                                .kind(CustomModelPrice.Kind.FIXED)
                                .inputPrice("1")
                                .outputPrice("10")
                                .build())
                        .build())
                .lines(List.of(
                        line("input", "1", CostSource.ORGANIZATION),
                        line("output", "10", CostSource.ORGANIZATION),
                        line("cache_read", "0.025", CostSource.BUILT_IN),
                        line("cache_write", "0", CostSource.NONE)))
                .build());
    }

    @Test
    void aliasNamesTheOrganizationRowItLedTo() {
        var aliasRow = alias("aide-gpt-5-mini", "azure", "gpt-5-mini", "azure");
        var targetRow = fixed("gpt-5-mini", "azure", "1.00", "4.00");

        var resolution = ModelPriceExplainer.explain(request("aide-gpt-5-mini", "azure", 1000, 0, 500),
                layers(aliasRow, targetRow));

        // 1000*1e-6 + 500*4e-6
        assertThat(resolution).isEqualTo(CustomModelPriceResolution.builder()
                .model("aide-gpt-5-mini")
                .provider("azure")
                .cost("0.003")
                .costSource(CostSource.ORGANIZATION)
                .listVersion(LIST_VERSION)
                .match(CustomModelPriceResolution.Match.builder()
                        .provider("azure")
                        .nameForm("exact")
                        .row(CustomModelPriceRow.builder()
                                .model("aide-gpt-5-mini")
                                .provider("azure")
                                .kind(CustomModelPrice.Kind.ALIAS)
                                .aliasOf(CustomModelPriceRow.AliasOf.builder()
                                        .model("gpt-5-mini")
                                        .provider("azure")
                                        .build())
                                .build())
                        .targetRow(CustomModelPriceRow.builder()
                                .model("gpt-5-mini")
                                .provider("azure")
                                .kind(CustomModelPrice.Kind.FIXED)
                                .inputPrice("1")
                                .outputPrice("4")
                                .build())
                        .build())
                .lines(List.of(
                        line("input", "1", CostSource.ORGANIZATION),
                        line("output", "4", CostSource.ORGANIZATION),
                        line("cache_read", "0.025", CostSource.BUILT_IN),
                        line("cache_write", "0", CostSource.NONE)))
                .build());
    }

    @Test
    void aliasToABuiltInPriceTakesEveryRateFromIt() {
        var aliasRow = alias("gpt-5-mini", GATEWAY_HOST, "gpt-5-mini", "azure");

        var resolution = ModelPriceExplainer.explain(request("gpt-5-mini", GATEWAY_HOST, 1000, 0, 500),
                layers(aliasRow));

        // 1000*2.5e-7 + 500*2e-6, azure's gpt-5-mini
        assertThat(resolution).isEqualTo(CustomModelPriceResolution.builder()
                .model("gpt-5-mini")
                .provider(GATEWAY_HOST)
                .cost("0.00125")
                .costSource(CostSource.ORGANIZATION)
                .listVersion(LIST_VERSION)
                .match(CustomModelPriceResolution.Match.builder()
                        .provider(GATEWAY_HOST)
                        .nameForm("exact")
                        .row(CustomModelPriceRow.from(aliasRow))
                        .build())
                .lines(List.of(
                        line("input", "0.25", CostSource.BUILT_IN),
                        line("output", "2", CostSource.BUILT_IN),
                        line("cache_read", "0.025", CostSource.BUILT_IN),
                        line("cache_write", "0", CostSource.NONE)))
                .build());
    }

    @Test
    void builtInPriceHasNoOrganizationRow() {
        var resolution = ModelPriceExplainer.explain(request("gpt-5-mini", "openai", 1000, 0, 500),
                PriceLayers.BUILT_IN_ONLY);

        // 1000*2.5e-7 + 500*2e-6
        assertThat(resolution).isEqualTo(CustomModelPriceResolution.builder()
                .model("gpt-5-mini")
                .provider("openai")
                .cost("0.00125")
                .costSource(CostSource.BUILT_IN)
                .listVersion(0)
                .match(CustomModelPriceResolution.Match.builder().provider("openai").nameForm("exact").build())
                .lines(List.of(
                        line("input", "0.25", CostSource.BUILT_IN),
                        line("output", "2", CostSource.BUILT_IN),
                        line("cache_read", "0.025", CostSource.BUILT_IN),
                        line("cache_write", "0", CostSource.NONE)))
                .build());
    }

    @Test
    void unpricedModelHasNoMatchAndNoLines() {
        var resolution = ModelPriceExplainer.explain(request("aide-gpt-5-mini", "azure", 1000, 0, 500),
                PriceLayers.BUILT_IN_ONLY);

        assertThat(resolution).isEqualTo(CustomModelPriceResolution.builder()
                .model("aide-gpt-5-mini")
                .provider("azure")
                .cost("0")
                .costSource(CostSource.NONE)
                .listVersion(0)
                .lines(List.of())
                .build());
    }

    @Test
    void withoutUsageTheRatesStillShowAndNothingIsCharged() {
        var row = fixed("gpt-5-mini", "openai", "1.00", "10.00");

        var resolution = ModelPriceExplainer.explain(
                CustomModelPriceResolveRequest.builder().model("gpt-5-mini").provider("openai").build(),
                layers(row));

        assertThat(resolution).isEqualTo(CustomModelPriceResolution.builder()
                .model("gpt-5-mini")
                .provider("openai")
                .cost("0")
                .costSource(CostSource.NONE)
                .listVersion(LIST_VERSION)
                .match(CustomModelPriceResolution.Match.builder()
                        .provider("openai")
                        .nameForm("exact")
                        .row(CustomModelPriceRow.from(row))
                        .build())
                .lines(List.of(
                        line("input", "1", CostSource.ORGANIZATION),
                        line("output", "10", CostSource.ORGANIZATION),
                        line("cache_read", "0.025", CostSource.BUILT_IN),
                        line("cache_write", "0", CostSource.NONE)))
                .build());
    }
}
