package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostServiceTest {

    @Test
    void testTts1Cost() {
        Map<String, Integer> usage = Map.of("character_count", 2000);
        BigDecimal cost = CostService.calculateCost("tts-1", "openai", usage, null);
        // 2000 * 0.000015 = 0.03
        assertEquals(new BigDecimal("0.03"), cost.setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void testTts1HdCost() {
        Map<String, Integer> usage = Map.of("character_count", 1500);
        BigDecimal cost = CostService.calculateCost("tts-1-hd", "openai", usage, null);
        // 1500 * 0.00003 = 0.045
        assertEquals(new BigDecimal("0.045"), cost.setScale(3, RoundingMode.HALF_UP));
    }
}