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
}
