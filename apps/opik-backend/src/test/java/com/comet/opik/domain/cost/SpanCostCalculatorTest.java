package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Map;
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

        assertThat(cost).isEqualByComparingTo("0.015");
    }

    // --- Cache Cost OTel Fallback Tests ---

    @Test
    void textGenerationWithCacheCostOpenAIUsesOtelCacheReadKey() {
        ModelPrice modelPrice = new ModelPrice(new BigDecimal("0.01"), new BigDecimal("0.02"),
                BigDecimal.ZERO, new BigDecimal("0.005"), BigDecimal.ZERO, BigDecimal.ZERO,
                SpanCostCalculator::textGenerationWithCacheCostOpenAI);

        // Simulate LiteLLM OTel span: prompt_tokens=1000, cache_read_input_tokens=200
        // OpenAI style: cached tokens are included in prompt_tokens, so non-cached = 800
        BigDecimal cost = SpanCostCalculator.textGenerationWithCacheCostOpenAI(modelPrice,
                Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                        "cache_read_input_tokens", 200));

        // inputTokens = 1000 - 200 = 800, outputTokens = 100, cachedRead = 200
        BigDecimal expected = new BigDecimal("0.01").multiply(new BigDecimal("800"))
                .add(new BigDecimal("0.02").multiply(new BigDecimal("100")))
                .add(new BigDecimal("0.005").multiply(new BigDecimal("200")));
        assertThat(cost).isEqualByComparingTo(expected);
    }

    @Test
    void textGenerationWithCacheCostOpenAIOriginalUsageKeyTakesPrecedenceOverOtelKey() {
        ModelPrice modelPrice = new ModelPrice(new BigDecimal("0.01"), new BigDecimal("0.02"),
                BigDecimal.ZERO, new BigDecimal("0.005"), BigDecimal.ZERO, BigDecimal.ZERO,
                SpanCostCalculator::textGenerationWithCacheCostOpenAI);

        // Both keys present: original_usage key should take precedence
        BigDecimal cost = SpanCostCalculator.textGenerationWithCacheCostOpenAI(modelPrice,
                Map.of("original_usage.prompt_tokens", 1000, "original_usage.completion_tokens", 100,
                        "original_usage.prompt_tokens_details.cached_tokens", 300,
                        "cache_read_input_tokens", 200));

        // original_usage.prompt_tokens_details.cached_tokens=300 takes precedence over cache_read_input_tokens=200
        BigDecimal expected = new BigDecimal("0.01").multiply(new BigDecimal("700"))
                .add(new BigDecimal("0.02").multiply(new BigDecimal("100")))
                .add(new BigDecimal("0.005").multiply(new BigDecimal("300")));
        assertThat(cost).isEqualByComparingTo(expected);
    }

    @Test
    void textGenerationWithCacheCostAnthropicUsesOtelCacheReadKey() {
        ModelPrice modelPrice = new ModelPrice(new BigDecimal("0.01"), new BigDecimal("0.02"),
                new BigDecimal("0.015"), new BigDecimal("0.005"), BigDecimal.ZERO, BigDecimal.ZERO,
                SpanCostCalculator::textGenerationWithCacheCostAnthropic);

        // Simulate LiteLLM OTel span for Anthropic: bare keys without original_usage. prefix
        BigDecimal cost = SpanCostCalculator.textGenerationWithCacheCostAnthropic(modelPrice,
                Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                        "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50));

        // input=1000, output=100, cacheRead=200, cacheCreation=50
        BigDecimal expected = new BigDecimal("0.01").multiply(new BigDecimal("1000"))
                .add(new BigDecimal("0.02").multiply(new BigDecimal("100")))
                .add(new BigDecimal("0.015").multiply(new BigDecimal("50")))
                .add(new BigDecimal("0.005").multiply(new BigDecimal("200")));
        assertThat(cost).isEqualByComparingTo(expected);
    }

    @Test
    void textGenerationWithCacheCostBedrockUsesOtelCacheReadKey() {
        ModelPrice modelPrice = new ModelPrice(new BigDecimal("0.01"), new BigDecimal("0.02"),
                new BigDecimal("0.015"), new BigDecimal("0.005"), BigDecimal.ZERO, BigDecimal.ZERO,
                SpanCostCalculator::textGenerationWithCacheCostBedrock);

        // Simulate LiteLLM OTel span for Bedrock: bare keys without original_usage. prefix
        BigDecimal cost = SpanCostCalculator.textGenerationWithCacheCostBedrock(modelPrice,
                Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                        "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50));

        // input=1000, output=100, cacheRead=200, cacheCreation=50
        BigDecimal expected = new BigDecimal("0.01").multiply(new BigDecimal("1000"))
                .add(new BigDecimal("0.02").multiply(new BigDecimal("100")))
                .add(new BigDecimal("0.015").multiply(new BigDecimal("50")))
                .add(new BigDecimal("0.005").multiply(new BigDecimal("200")));
        assertThat(cost).isEqualByComparingTo(expected);
    }
}
