package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

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

    @Test
    void audioSpeechCostReturnsZeroWhenPriceIsZero() {
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, SpanCostCalculator::defaultCost);

        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice, Map.of("input_characters", 100));

        assertThat(cost).isZero();
    }

    @Test
    void audioSpeechCostReturnsZeroWhenNoCharacters() {
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.000015"), SpanCostCalculator::defaultCost);

        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice, Map.of());

        assertThat(cost).isZero();
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

    @Test
    void audioSpeechCostMultipliesCharactersAndPrice_tts1() {
        // tts-1: $0.000015 per character
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.000015"), SpanCostCalculator::defaultCost);

        // 1000 characters → $0.015
        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice, Map.of("input_characters", 1000));

        assertThat(cost).isEqualByComparingTo("0.015");
    }

    @Test
    void audioSpeechCostMultipliesCharactersAndPrice_tts1hd() {
        // tts-1-hd: $0.000030 per character
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.000030"), SpanCostCalculator::defaultCost);

        // 500 characters → $0.015
        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice, Map.of("input_characters", 500));

        assertThat(cost).isEqualByComparingTo("0.015");
    }
}
