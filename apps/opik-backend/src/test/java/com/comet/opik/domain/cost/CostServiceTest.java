package com.comet.opik.domain.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CostServiceTest {

    @Test
    void testTts1Cost() {
        Map<String, Integer> usage = Map.of("character_count", 2000);
        BigDecimal cost = CostService.calculateCost("tts-1", "openai", usage, null);
        BigDecimal unit = CostService.calculateCost("tts-1", "openai", Map.of("character_count", 1), null);
        assertEquals(unit.multiply(BigDecimal.valueOf(2000)).setScale(6, RoundingMode.HALF_UP), cost);
    }

    @Test
    void testTts1HdCost() {
        Map<String, Integer> usage = Map.of("character_count", 1500);
        BigDecimal cost = CostService.calculateCost("tts-1-hd", "openai", usage, null);
        BigDecimal unit = CostService.calculateCost("tts-1-hd", "openai", Map.of("character_count", 1), null);
        assertEquals(unit.multiply(BigDecimal.valueOf(1500)).setScale(6, RoundingMode.HALF_UP), cost);
    }
}