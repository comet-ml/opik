package com.comet.opik.domain.cost;

import com.comet.opik.domain.token_usage.SpanUsageFactory;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceMergerTest {

    private static BigDecimal randomPerMillion() {
        return BigDecimal.valueOf(RandomUtils.secure().randomLong(1, 100_000), 2);
    }

    private static BigDecimal perToken(BigDecimal perMillion) {
        return perMillion.movePointLeft(6);
    }

    private static BigDecimal randomRate() {
        return perToken(randomPerMillion());
    }

    private static BigDecimal randomRatio() {
        return BigDecimal.valueOf(RandomUtils.secure().randomLong(1, 1_000), 2);
    }

    private static ModelPrice randomTextPrice() {
        return ModelPrice.defaultBuilder()
                .inputPrice(randomRate())
                .outputPrice(randomRate())
                .cacheReadInputTokenPrice(randomRate())
                .calculator(SpanCostCalculator::textGenerationCost)
                .build();
    }

    private static CustomModelPrice.CustomModelPriceBuilder fixedRow() {
        return CustomModelPrice.builder()
                .modelName(RandomStringUtils.secure().nextAlphanumeric(10))
                .provider(RandomStringUtils.secure().nextAlphanumeric(10))
                .kind(CustomModelPrice.Kind.FIXED);
    }

    @Test
    void mergeReplacesEachRateTheRowSetsAndKeepsTheOthers() {
        var base = randomTextPrice();
        var inputPerMillion = randomPerMillion();
        var outputPerMillion = randomPerMillion();
        var row = fixedRow().inputPerMillion(inputPerMillion).outputPerMillion(outputPerMillion).build();

        ModelPrice merged = ModelPriceMerger.merge(base, row);

        assertThat(merged.inputPrice()).isEqualByComparingTo(perToken(inputPerMillion));
        assertThat(merged.outputPrice()).isEqualByComparingTo(perToken(outputPerMillion));
        assertThat(merged.cacheReadInputTokenPrice()).isEqualByComparingTo(base.cacheReadInputTokenPrice());
        assertThat(merged.calculator()).isSameAs(base.calculator());
    }

    @Test
    void mergeZeroesTheRateTheRowSetsInEveryTier() {
        var tier = ModelPrice.PromptTier.builder()
                .threshold(RandomUtils.secure().randomInt(32_000, 512_000))
                .inputPrice(randomRate())
                .outputPrice(randomRate())
                .cacheCreationInputTokenPrice(BigDecimal.ZERO)
                .cacheReadInputTokenPrice(randomRate())
                .build();
        var base = randomTextPrice().toBuilder().promptTiers(List.of(tier)).build();
        var inputPerMillion = randomPerMillion();
        var row = fixedRow().inputPerMillion(inputPerMillion).build();
        int promptAboveTier = tier.threshold() + 1;

        ModelPrice merged = ModelPriceMerger.merge(base, row);

        assertThat(merged.effectiveInputPrice(promptAboveTier)).isEqualByComparingTo(perToken(inputPerMillion));
        assertThat(merged.effectiveOutputPrice(promptAboveTier)).isEqualByComparingTo(tier.outputPrice());
        assertThat(merged.effectiveCacheReadInputTokenPrice(promptAboveTier))
                .isEqualByComparingTo(tier.cacheReadInputTokenPrice());
    }

    @Test
    void mergeKeepsTheBaseRatioOfRatesTheRowHasNoFieldFor() {
        var baseInput = randomRate();
        var baseOutput = randomRate();
        var baseCacheWrite = randomRate();
        var inputAudioRatio = randomRatio();
        var outputReasoningRatio = randomRatio();
        var outputAudioRatio = randomRatio();
        var oneHourWriteRatio = randomRatio();
        var base = ModelPrice.defaultBuilder()
                .inputPrice(baseInput)
                .inputAudioTokenPrice(baseInput.multiply(inputAudioRatio))
                .outputPrice(baseOutput)
                .outputReasoningTokenPrice(baseOutput.multiply(outputReasoningRatio))
                .outputAudioTokenPrice(baseOutput.multiply(outputAudioRatio))
                .cacheCreationInputTokenPrice(baseCacheWrite)
                .cacheCreationInputTokenPriceAbove1hr(baseCacheWrite.multiply(oneHourWriteRatio))
                .calculator(SpanCostCalculator::textGenerationCost)
                .build();
        var inputPerMillion = randomPerMillion();
        var outputPerMillion = randomPerMillion();
        var cacheWritePerMillion = randomPerMillion();
        var row = fixedRow()
                .inputPerMillion(inputPerMillion)
                .outputPerMillion(outputPerMillion)
                .cacheWritePerMillion(cacheWritePerMillion)
                .build();
        var input = perToken(inputPerMillion);
        var output = perToken(outputPerMillion);
        var cacheWrite = perToken(cacheWritePerMillion);

        ModelPrice merged = ModelPriceMerger.merge(base, row);

        assertThat(merged.inputAudioTokenPrice()).isEqualByComparingTo(input.multiply(inputAudioRatio));
        assertThat(merged.outputReasoningTokenPrice()).isEqualByComparingTo(output.multiply(outputReasoningRatio));
        assertThat(merged.outputAudioTokenPrice()).isEqualByComparingTo(output.multiply(outputAudioRatio));
        assertThat(merged.cacheCreationInputTokenPrice()).isEqualByComparingTo(cacheWrite);
        assertThat(merged.cacheCreationInputTokenPriceAbove1hr())
                .isEqualByComparingTo(cacheWrite.multiply(oneHourWriteRatio));
    }

    @Test
    void mergeWithAFreeOutputRateMakesReasoningFree() {
        var base = randomTextPrice().toBuilder().outputReasoningTokenPrice(randomRate()).build();
        var row = fixedRow().outputPerMillion(BigDecimal.ZERO).build();

        ModelPrice merged = ModelPriceMerger.merge(base, row);

        assertThat(merged.outputReasoningTokenPrice()).isEqualByComparingTo("0");
    }

    @Test
    void mergeOverAnEmptyBasePricesTheTokens() {
        var inputPerMillion = randomPerMillion();
        var outputPerMillion = randomPerMillion();
        var row = fixedRow().provider(CustomModelPrice.ANY_PROVIDER)
                .inputPerMillion(inputPerMillion).outputPerMillion(outputPerMillion).build();
        int promptTokens = RandomUtils.secure().randomInt(1, 100_000);
        int completionTokens = RandomUtils.secure().randomInt(1, 100_000);
        var usage = SpanUsageFactory.create(null,
                Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens));
        var expected = perToken(inputPerMillion).multiply(BigDecimal.valueOf(promptTokens))
                .add(perToken(outputPerMillion).multiply(BigDecimal.valueOf(completionTokens)));

        ModelPrice merged = ModelPriceMerger.merge(ModelPrice.empty(), row);

        assertThat(merged.calculator().apply(merged, usage)).isEqualByComparingTo(expected);
    }

    @Test
    void mergeKeepsAPerUnitCalculator() {
        var speech = ModelPrice.defaultBuilder()
                .audioInputCharacterPrice(randomRate())
                .calculator(SpanCostCalculator::audioSpeechCost)
                .build();
        var row = fixedRow().inputPerMillion(randomPerMillion()).build();

        assertThat(ModelPriceMerger.merge(speech, row).calculator()).isSameAs(speech.calculator());
    }

    @Test
    void mergeWithNoRateSetReturnsTheBasePrice() {
        var base = randomTextPrice();

        assertThat(ModelPriceMerger.merge(base, fixedRow().build())).isEqualTo(base);
    }
}
