package com.comet.opik.domain.cost;

import com.comet.opik.domain.token_usage.SpanUsageFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceMergerTest {

    private static final ModelPrice GPT_5_MINI = ModelPrice.defaultBuilder()
            .inputPrice(new BigDecimal("2.5E-7"))
            .outputPrice(new BigDecimal("0.000002"))
            .cacheReadInputTokenPrice(new BigDecimal("2.5E-8"))
            .calculator(SpanCostCalculator::textGenerationCost)
            .build();

    private static CustomModelPrice.CustomModelPriceBuilder fixedRow() {
        return CustomModelPrice.builder()
                .modelName("gpt-5-mini")
                .provider("openai")
                .kind(CustomModelPrice.Kind.FIXED);
    }

    @Test
    void mergeReplacesEachRateTheRowSetsAndKeepsTheOthers() {
        var row = fixedRow().inputPerMillion(new BigDecimal("1.00")).outputPerMillion(new BigDecimal("10.00")).build();

        ModelPrice merged = ModelPriceMerger.merge(GPT_5_MINI, row);

        assertThat(merged.inputPrice()).isEqualByComparingTo("0.000001");
        assertThat(merged.outputPrice()).isEqualByComparingTo("0.00001");
        assertThat(merged.cacheReadInputTokenPrice()).isEqualByComparingTo("2.5E-8");
        assertThat(merged.calculator()).isSameAs(GPT_5_MINI.calculator());
    }

    @Test
    void mergeZeroesTheRateTheRowSetsInEveryTier() {
        var gpt54 = ModelPrice.defaultBuilder()
                .inputPrice(new BigDecimal("0.0000025"))
                .outputPrice(new BigDecimal("0.000015"))
                .cacheReadInputTokenPrice(new BigDecimal("2.5E-7"))
                .promptTiers(List.of(ModelPrice.PromptTier.builder()
                        .threshold(272_000)
                        .inputPrice(new BigDecimal("0.000005"))
                        .outputPrice(new BigDecimal("0.0000225"))
                        .cacheCreationInputTokenPrice(BigDecimal.ZERO)
                        .cacheReadInputTokenPrice(new BigDecimal("5E-7"))
                        .build()))
                .build();
        var row = fixedRow().modelName("gpt-5.4").inputPerMillion(new BigDecimal("2.00")).build();

        ModelPrice merged = ModelPriceMerger.merge(gpt54, row);

        assertThat(merged.effectiveInputPrice(300_000)).isEqualByComparingTo("0.000002");
        assertThat(merged.effectiveOutputPrice(300_000)).isEqualByComparingTo("0.0000225");
        assertThat(merged.effectiveCacheReadInputTokenPrice(300_000)).isEqualByComparingTo("5E-7");
    }

    @Test
    void mergeKeepsTheBaseRatioOfRatesTheRowHasNoFieldFor() {
        var base = ModelPrice.defaultBuilder()
                .inputPrice(new BigDecimal("0.000001"))
                .inputAudioTokenPrice(new BigDecimal("0.000004"))
                .outputPrice(new BigDecimal("0.000002"))
                .outputReasoningTokenPrice(new BigDecimal("0.000002"))
                .outputAudioTokenPrice(new BigDecimal("0.000008"))
                .cacheCreationInputTokenPrice(new BigDecimal("0.00000375"))
                .cacheCreationInputTokenPriceAbove1hr(new BigDecimal("0.000006"))
                .calculator(SpanCostCalculator::textGenerationCost)
                .build();
        var row = fixedRow()
                .inputPerMillion(new BigDecimal("2.00"))
                .outputPerMillion(new BigDecimal("10.00"))
                .cacheWritePerMillion(new BigDecimal("3.00"))
                .build();

        ModelPrice merged = ModelPriceMerger.merge(base, row);

        assertThat(merged.inputAudioTokenPrice()).isEqualByComparingTo("0.000008");
        assertThat(merged.outputReasoningTokenPrice()).isEqualByComparingTo("0.00001");
        assertThat(merged.outputAudioTokenPrice()).isEqualByComparingTo("0.00004");
        assertThat(merged.cacheCreationInputTokenPrice()).isEqualByComparingTo("0.000003");
        assertThat(merged.cacheCreationInputTokenPriceAbove1hr()).isEqualByComparingTo("0.0000048");
    }

    @Test
    void mergeWithAFreeOutputRateMakesReasoningFree() {
        var base = GPT_5_MINI.toBuilder().outputReasoningTokenPrice(new BigDecimal("0.000002")).build();
        var row = fixedRow().outputPerMillion(BigDecimal.ZERO).build();

        ModelPrice merged = ModelPriceMerger.merge(base, row);

        assertThat(merged.outputReasoningTokenPrice()).isEqualByComparingTo("0");
    }

    @Test
    void mergeOverAnEmptyBasePricesTheTokens() {
        var row = fixedRow().modelName("aide-gpt-5-mini").provider(CustomModelPrice.ANY_PROVIDER)
                .inputPerMillion(new BigDecimal("1.10")).outputPerMillion(new BigDecimal("4.40")).build();
        var usage = SpanUsageFactory.create(null, Map.of("prompt_tokens", 1000, "completion_tokens", 500));

        ModelPrice merged = ModelPriceMerger.merge(ModelPrice.empty(), row);

        assertThat(merged.calculator().apply(merged, usage)).isEqualByComparingTo("0.0033");
    }

    @Test
    void mergeKeepsAPerUnitCalculator() {
        var speech = ModelPrice.defaultBuilder()
                .audioInputCharacterPrice(new BigDecimal("0.000015"))
                .calculator(SpanCostCalculator::audioSpeechCost)
                .build();
        var row = fixedRow().modelName("tts-1").inputPerMillion(new BigDecimal("1.00")).build();

        assertThat(ModelPriceMerger.merge(speech, row).calculator()).isSameAs(speech.calculator());
    }

    @Test
    void mergeWithNoRateSetReturnsTheBasePrice() {
        assertThat(ModelPriceMerger.merge(GPT_5_MINI, fixedRow().build())).isEqualTo(GPT_5_MINI);
    }
}
