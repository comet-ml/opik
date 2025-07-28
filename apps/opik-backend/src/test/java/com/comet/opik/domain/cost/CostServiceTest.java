package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostServiceTest {

    @Test
    void testTts1Cost() {
        Map<String, Integer> usage = Map.of("character_count", 2000);
        BigDecimal cost = CostService.calculateCost("tts-1", "openai", usage, null);
        BigDecimal unit = CostService.calculateCost("tts-1", "openai", Map.of("character_count", 1), null);
        BigDecimal expected = unit.multiply(BigDecimal.valueOf(2000));
        assertEquals(0, expected.compareTo(cost));
    }

    @Test
    void testTts1HdCost() {
        Map<String, Integer> usage = Map.of("character_count", 1500);
        BigDecimal cost = CostService.calculateCost("tts-1-hd", "openai", usage, null);
        BigDecimal unit = CostService.calculateCost("tts-1-hd", "openai", Map.of("character_count", 1), null);
        BigDecimal expected = unit.multiply(BigDecimal.valueOf(1500));
        assertEquals(0, expected.compareTo(cost));
    }
}