package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanCostCalculatorTest {

    @Test
    void videoGenerationCostReturnsZeroWhenPriceIsZero() {
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, SpanCostCalculator::defaultCost);

        BigDecimal cost = SpanCostCalculator.videoGenerationCost(modelPrice, Map.of("video_duration_seconds", 10));

        assertThat(cost).isZero();
    }

    @Test
    void videoGenerationCostValidatesArguments() {
        assertThatThrownBy(() -> SpanCostCalculator.videoGenerationCost(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, SpanCostCalculator::textGenerationCost);
        assertThatThrownBy(() -> SpanCostCalculator.videoGenerationCost(modelPrice, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void videoGenerationCostMultipliesDurationAndPrice() {
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.5"), BigDecimal.ZERO, SpanCostCalculator::defaultCost);

        BigDecimal cost = SpanCostCalculator.videoGenerationCost(modelPrice, Map.of("video_duration_seconds", 2));

        // 2 seconds * 0.5 = 1.0
        assertThat(cost).isEqualByComparingTo("1.0");
    }

    // --- Audio Speech Cost Tests ---

    @ParameterizedTest
    @MethodSource("provideAudioSpeechZeroCostCases")
    void audioSpeechCostReturnsZero(String pricePerChar, Map<String, Integer> usage) {
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal(pricePerChar), SpanCostCalculator::defaultCost);

        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice, usage);

        assertThat(cost).isZero();
    }

    private static Stream<Arguments> provideAudioSpeechZeroCostCases() {
        return Stream.of(
                // Zero price per character → zero cost
                Arguments.of("0", Map.of("input_characters", 100)),
                // Non-zero price but no characters in usage → zero cost
                Arguments.of("0.000015", Map.of()));
    }

    @Test
    void audioSpeechCostValidatesArguments() {
        assertThatThrownBy(() -> SpanCostCalculator.audioSpeechCost(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.000015"), SpanCostCalculator::defaultCost);
        assertThatThrownBy(() -> SpanCostCalculator.audioSpeechCost(modelPrice, null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @MethodSource("provideAudioSpeechCostCases")
    void audioSpeechCostMultipliesCharactersAndPrice(String pricePerChar, int inputCharacters, String expectedCost) {
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal(pricePerChar), SpanCostCalculator::defaultCost);

        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice, Map.of("input_characters", inputCharacters));

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideAudioSpeechCostCases() {
        return Stream.of(
                // tts-1: $0.000015 per character, 1000 characters → $0.015
                Arguments.of("0.000015", 1000, "0.015"),
                // tts-1-hd: $0.000030 per character, 500 characters → $0.015
                Arguments.of("0.000030", 500, "0.015"));
    }

    @Test
    void audioSpeechCostUsesOriginalUsagePrefix() {
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.000015"), SpanCostCalculator::defaultCost);

        // SDK 1.6.0+ sends usage with original_usage. prefix
        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice,
                Map.of("original_usage.input_characters", 1000));

        // 1000 characters * 0.000015 = 0.015
        assertThat(cost).isEqualByComparingTo("0.015");
    }

    // --- Cache Cost OTel Fallback Tests ---

    @ParameterizedTest(name = "{1}")
    @MethodSource("provideOpenAICacheCostCases")
    void textGenerationWithCacheCostOpenAI(Map<String, Integer> usage, String description, String expectedCost) {
        ModelPrice modelPrice = new ModelPrice(new BigDecimal("0.01"), new BigDecimal("0.02"),
                BigDecimal.ZERO, new BigDecimal("0.005"), BigDecimal.ZERO, BigDecimal.ZERO,
                SpanCostCalculator::textGenerationWithCacheCostOpenAI);

        BigDecimal cost = SpanCostCalculator.textGenerationWithCacheCostOpenAI(modelPrice, usage);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideOpenAICacheCostCases() {
        return Stream.of(
                // OTel key: prompt=1000, cache_read=200 → non-cached input=800
                // 800*0.01 + 100*0.02 + 200*0.005 = 8.00 + 2.00 + 1.00 = 11.00
                Arguments.of(
                        Map.of("prompt_tokens", 1000, "completion_tokens", 100, "cache_read_input_tokens", 200),
                        "OTel cache_read_input_tokens key",
                        "11.00"),
                // original_usage key takes precedence: cached=300 overrides cache_read_input_tokens=200
                // 700*0.01 + 100*0.02 + 300*0.005 = 7.00 + 2.00 + 1.50 = 10.50
                Arguments.of(
                        Map.of("original_usage.prompt_tokens", 1000, "original_usage.completion_tokens", 100,
                                "original_usage.prompt_tokens_details.cached_tokens", 300,
                                "cache_read_input_tokens", 200),
                        "original_usage key takes precedence over OTel key",
                        "10.50"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("provideAnthropicBedrockCacheCostCases")
    void textGenerationWithCacheCostUsesOtelCacheKeyFallback(
            BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator, String description) {
        ModelPrice modelPrice = new ModelPrice(new BigDecimal("0.01"), new BigDecimal("0.02"),
                new BigDecimal("0.015"), new BigDecimal("0.005"), BigDecimal.ZERO, BigDecimal.ZERO,
                SpanCostCalculator::defaultCost);

        BigDecimal cost = calculator.apply(modelPrice,
                Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                        "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50));

        // input=1000*0.01 + output=100*0.02 + cacheCreation=50*0.015 + cacheRead=200*0.005
        // = 10.00 + 2.00 + 0.75 + 1.00 = 13.75
        assertThat(cost).isEqualByComparingTo("13.75");
    }

    private static Stream<Arguments> provideAnthropicBedrockCacheCostCases() {
        BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> anthropic = SpanCostCalculator::textGenerationWithCacheCostAnthropic;
        BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> bedrock = SpanCostCalculator::textGenerationWithCacheCostBedrock;
        return Stream.of(
                Arguments.of(anthropic, "Anthropic"),
                Arguments.of(bedrock, "Bedrock"));
    }
}
