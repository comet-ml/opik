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
                BigDecimal.ZERO, SpanCostCalculator::defaultCost);

        BigDecimal cost = SpanCostCalculator.videoGenerationCost(modelPrice, Map.of("video_duration_seconds", 10));

        assertThat(cost).isZero();
    }

    @Test
    void videoGenerationCostValidatesArguments() {
        assertThatThrownBy(() -> SpanCostCalculator.videoGenerationCost(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, SpanCostCalculator::textGenerationCost);
        assertThatThrownBy(() -> SpanCostCalculator.videoGenerationCost(modelPrice, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void videoGenerationCostMultipliesDurationAndPrice() {
        ModelPrice modelPrice = new ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.5"), SpanCostCalculator::defaultCost);

        BigDecimal cost = SpanCostCalculator.videoGenerationCost(modelPrice, Map.of("video_duration_seconds", 2));

        assertThat(cost).isEqualByComparingTo("1.0");
    }
}
