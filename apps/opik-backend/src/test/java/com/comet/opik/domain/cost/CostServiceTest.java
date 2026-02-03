package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CostServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void calculateCostForVideoGenerationUsesDuration() {
        BigDecimal cost = CostService.calculateCost("sora-2", "openai",
                Map.of("video_duration_seconds", 4), null);

        assertThat(cost).isEqualByComparingTo("0.4");
    }

    @Test
    void calculateCostUsesCacheAwareCalculatorWhenCachePricesConfigured() {
        Map<String, Integer> usage = Map.of(
                "original_usage.inputTokens", 100,
                "original_usage.outputTokens", 20,
                "original_usage.cacheReadInputTokens", 10,
                "original_usage.cacheWriteInputTokens", 5);

        BigDecimal cost = CostService.calculateCost("anthropic.claude-3-5-haiku-20241022-v1:0", "bedrock", usage, null);

        assertThat(cost).isEqualByComparingTo("0.0001658");
    }

    @Test
    void calculateCostFallsBackToMetadataWhenNoMatchingModelFound() {
        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        metadata.putObject("cost")
                .put("currency", "USD")
                .put("total_tokens", 0.42);

        BigDecimal cost = CostService.calculateCost("unknown-model", "unknown", Map.of(), metadata);

        assertThat(cost).isEqualByComparingTo("0.42");
    }

    /**
     * Test for issue #4114: Cost estimate not showing for recent Claude models.
     *
     * This test verifies that model names with dots (e.g., "claude-3.5-sonnet")
     * are correctly normalized to hyphens (e.g., "claude-3-5-sonnet") to match
     * the pricing database format. It also tests case insensitivity and backwards
     * compatibility.
     *
     * Parameterized test covering both positive (cost > 0) and edge (cost = 0) cases.
     */
    @ParameterizedTest
    @MethodSource("provideModelNamesForNormalization")
    void calculateCost_shouldNormalizeModelNames_issue4114(String modelName, String provider, boolean shouldHaveCost) {
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 500);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        if (shouldHaveCost) {
            assertThat(cost).isGreaterThan(BigDecimal.ZERO);
        } else {
            assertThat(cost).isEqualTo(BigDecimal.ZERO);
        }
    }

    private static Stream<Arguments> provideModelNamesForNormalization() {
        return Stream.of(
                // Dot notation should work (normalized to hyphens)
                Arguments.of("claude-3.5-sonnet-20241022", "anthropic", true),
                Arguments.of("claude-sonnet-4.5", "anthropic", true),
                Arguments.of("claude-haiku-4.5", "anthropic", true),
                Arguments.of("claude-sonnet-4.5-20250929", "anthropic", true),
                Arguments.of("claude-haiku-4.5-20251001", "anthropic", true),

                // Case insensitivity should work
                Arguments.of("Claude-3.5-Sonnet-20241022", "anthropic", true),
                Arguments.of("CLAUDE-SONNET-4.5", "anthropic", true),

                // Backwards compatibility - exact matches still work
                Arguments.of("claude-3-5-sonnet-20241022", "anthropic", true),
                Arguments.of("claude-haiku-4-5", "anthropic", true),
                Arguments.of("claude-sonnet-4-5", "anthropic", true),

                // Unknown models should gracefully return zero
                Arguments.of("claude-3.5.1", "anthropic", false),
                Arguments.of("unknown-model-with-dots.1.2.3", "unknown", false));
    }

    /**
     * Test for issue #5018: GPT-5.2 costs are not tracked.
     *
     * This test verifies that model names with date suffixes (e.g., "gpt-5.2-2025-12-17")
     * are correctly handled by stripping the date suffix and falling back to the base
     * model name (e.g., "gpt-5.2") when the dated variant is not in the pricing database.
     */
    @ParameterizedTest
    @MethodSource("provideModelNamesWithDateSuffixes")
    void calculateCost_shouldStripDateSuffixes_issue5018(String modelName, String provider, boolean shouldHaveCost) {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 500);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        if (shouldHaveCost) {
            assertThat(cost).isGreaterThan(BigDecimal.ZERO);
        } else {
            assertThat(cost).isEqualTo(BigDecimal.ZERO);
        }
    }

    private static Stream<Arguments> provideModelNamesWithDateSuffixes() {
        return Stream.of(
                // Date suffixes should be stripped and fallback to base model
                Arguments.of("gpt-5.2-2025-12-17", "openai", true),
                Arguments.of("gpt-5.2-2026-01-15", "openai", true),
                Arguments.of("gpt-5.1-2025-11-13", "openai", true),
                Arguments.of("GPT-5.2-2025-12-17", "openai", true),

                // Base models should still work
                Arguments.of("gpt-5.2", "openai", true),
                Arguments.of("gpt-5.1", "openai", true),

                // Dot notation with date suffix
                Arguments.of("gpt-5.2-2025-12-17", "openai", true),

                // Unknown models with date suffixes should return zero
                Arguments.of("unknown-model-2025-12-17", "openai", false));
    }
}
