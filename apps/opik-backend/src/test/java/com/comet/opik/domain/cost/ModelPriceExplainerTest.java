package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPriceResolution;
import com.comet.opik.api.modelprice.CustomModelPriceResolveRequest;
import com.comet.opik.api.modelprice.CustomModelPriceRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceExplainerTest {

    private static final String GATEWAY_HOST = "llm-gateway.example.com";

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
        return PriceLayers.of("org-1", 12, List.of(rows));
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

    private static CustomModelPriceResolution.Line expectedLine(String name, String ratePerMillion, String source) {
        return CustomModelPriceResolution.Line.builder().name(name).ratePerMillion(ratePerMillion).source(source)
                .build();
    }

    private static CustomModelPriceResolution.Line line(CustomModelPriceResolution resolution, String name) {
        return resolution.lines().stream().filter(line -> line.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void fixedRowNamesItselfAndTheRatesItSets() {
        // 500k*1e-6 + 500k*2.5e-8 (built-in cache read) + 100k*1e-5
        var resolution = ModelPriceExplainer.explain(request("gpt-5-mini", "openai", 1_000_000, 500_000, 100_000),
                layers(fixed("gpt-5-mini", "openai", "1.00", "10.00")));

        assertThat(new BigDecimal(resolution.cost())).isEqualByComparingTo("1.5125");
        assertThat(resolution.costSource()).isEqualTo("organization");
        assertThat(resolution.listVersion()).isEqualTo(12);
        assertThat(resolution.match().provider()).isEqualTo("openai");
        assertThat(resolution.match().nameForm()).isEqualTo("exact");
        assertThat(resolution.match().row()).isEqualTo(CustomModelPriceRow.builder()
                .model("gpt-5-mini").provider("openai").kind("fixed").inputPrice("1").outputPrice("10").build());
        assertThat(resolution.match().targetRow()).isNull();
        assertThat(resolution.lines()).extracting(CustomModelPriceResolution.Line::name)
                .containsExactly("input", "output", "cache_read", "cache_write");
        assertThat(line(resolution, "input")).isEqualTo(expectedLine("input", "1",
                "organization"));
        assertThat(line(resolution, "output")).isEqualTo(expectedLine("output", "10",
                "organization"));
        assertThat(line(resolution, "cache_read")).isEqualTo(expectedLine("cache_read",
                "0.025", "built_in"));
    }

    @Test
    void aliasNamesTheOrganizationRowItLedTo() {
        // 1000*1e-6 + 500*4e-6
        var resolution = ModelPriceExplainer.explain(request("aide-gpt-5-mini", "azure", 1000, 0, 500),
                layers(alias("aide-gpt-5-mini", "azure", "gpt-5-mini", "azure"),
                        fixed("gpt-5-mini", "azure", "1.00", "4.00")));

        assertThat(new BigDecimal(resolution.cost())).isEqualByComparingTo("0.003");
        assertThat(resolution.costSource()).isEqualTo("organization");
        assertThat(resolution.match().row().kind()).isEqualTo("alias");
        assertThat(resolution.match().row().aliasOf())
                .isEqualTo(new CustomModelPriceRow.AliasOf("gpt-5-mini", "azure"));
        assertThat(resolution.match().targetRow().model()).isEqualTo("gpt-5-mini");
        assertThat(line(resolution, "input").source()).isEqualTo("organization");
        assertThat(line(resolution, "output").ratePerMillion()).isEqualTo("4");
        assertThat(line(resolution, "cache_read").source()).isEqualTo("built_in");
    }

    @Test
    void aliasToABuiltInPriceTakesEveryRateFromIt() {
        // 1000*2.5e-7 + 500*2e-6, azure's gpt-5-mini
        var resolution = ModelPriceExplainer.explain(request("gpt-5-mini", GATEWAY_HOST, 1000, 0, 500),
                layers(alias("gpt-5-mini", GATEWAY_HOST, "gpt-5-mini", "azure")));

        assertThat(new BigDecimal(resolution.cost())).isEqualByComparingTo("0.00125");
        assertThat(resolution.costSource()).isEqualTo("organization");
        assertThat(resolution.match().row().kind()).isEqualTo("alias");
        assertThat(resolution.match().targetRow()).isNull();
        assertThat(line(resolution, "input")).isEqualTo(expectedLine("input", "0.25",
                "built_in"));
        assertThat(line(resolution, "output")).isEqualTo(expectedLine("output", "2",
                "built_in"));
    }

    @Test
    void builtInPriceHasNoOrganizationRow() {
        var resolution = ModelPriceExplainer.explain(request("gpt-5-mini", "openai", 1000, 0, 500),
                PriceLayers.BUILT_IN_ONLY);

        assertThat(new BigDecimal(resolution.cost())).isEqualByComparingTo("0.00125");
        assertThat(resolution.costSource()).isEqualTo("built_in");
        assertThat(resolution.listVersion()).isZero();
        assertThat(resolution.match().row()).isNull();
        assertThat(line(resolution, "input").source()).isEqualTo("built_in");
    }

    @Test
    void unpricedModelHasNoMatchAndNoLines() {
        var resolution = ModelPriceExplainer.explain(request("aide-gpt-5-mini", "azure", 1000, 0, 500),
                PriceLayers.BUILT_IN_ONLY);

        assertThat(resolution.cost()).isEqualTo("0");
        assertThat(resolution.costSource()).isEqualTo("none");
        assertThat(resolution.match()).isNull();
        assertThat(resolution.lines()).isEmpty();
    }

    @Test
    void withoutUsageTheRatesStillShowAndNothingIsCharged() {
        var resolution = ModelPriceExplainer.explain(
                CustomModelPriceResolveRequest.builder().model("gpt-5-mini").provider("openai").build(),
                layers(fixed("gpt-5-mini", "openai", "1.00", "10.00")));

        assertThat(resolution.cost()).isEqualTo("0");
        assertThat(resolution.costSource()).isEqualTo("none");
        assertThat(resolution.match().row().model()).isEqualTo("gpt-5-mini");
        assertThat(line(resolution, "input").ratePerMillion()).isEqualTo("1");
        assertThat(line(resolution, "input").source()).isEqualTo("organization");
    }
}
