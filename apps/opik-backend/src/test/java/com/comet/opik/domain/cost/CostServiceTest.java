package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

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
     * the pricing database format.
     *
     * Backwards compatibility is maintained by trying exact match first.
     */
    @Test
    void calculateCost_shouldNormalizeModelNameWithDots_issue4114() {
        // Test case 1: claude-3.5-sonnet (with dot) should match claude-3-5-sonnet-* entries
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 500);

        BigDecimal cost = CostService.calculateCost("claude-3.5-sonnet-20241022", "anthropic", usage, null);

        // Should find pricing data (non-zero cost indicates model was found)
        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCost_shouldNormalizeClaudeSonnet45_issue4114() {
        // Test case 2: claude-sonnet-4.5 (with dot) should match claude-sonnet-4-5 entry
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 500);

        BigDecimal cost = CostService.calculateCost("claude-sonnet-4.5", "anthropic", usage, null);

        // Should find pricing data (non-zero cost indicates model was found)
        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCost_shouldNormalizeClaudeHaiku45_issue4114() {
        // Test case 3: claude-haiku-4.5 (with dot) should match claude-haiku-4-5 entry
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 500);

        BigDecimal cost = CostService.calculateCost("claude-haiku-4.5", "anthropic", usage, null);

        // Should find pricing data (non-zero cost indicates model was found)
        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCost_shouldMaintainBackwardsCompatibility_issue4114() {
        // Test case 4: Exact model names (without dots) should still work
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 500);

        // These exact model names should work as before
        BigDecimal cost1 = CostService.calculateCost("claude-3-5-sonnet-20241022", "anthropic", usage, null);
        BigDecimal cost2 = CostService.calculateCost("claude-haiku-4-5", "anthropic", usage, null);
        BigDecimal cost3 = CostService.calculateCost("claude-sonnet-4-5", "anthropic", usage, null);

        // All should return non-zero costs (backwards compatibility maintained)
        assertThat(cost1).isGreaterThan(BigDecimal.ZERO);
        assertThat(cost2).isGreaterThan(BigDecimal.ZERO);
        assertThat(cost3).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCost_shouldHandleMultipleDotsInModelName_issue4114() {
        // Test case 5: Model names with multiple dots should be normalized
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 500);

        // This would normalize "claude-3.5.1-sonnet" to "claude-3-5-1-sonnet"
        BigDecimal cost = CostService.calculateCost("claude-3.5.1", "anthropic", usage, null);

        // Even if not found in pricing database, should not throw exception
        assertThat(cost).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void calculateCost_shouldHandleVersionedClaudeModelsWithDots_issue4114() {
        // Test case 6: Specific models from user complaint in issue #4114
        // User reported: "claude-sonnet-4.5" and "claude-haiku-4.5" not showing costs
        // This also applies to versioned variants like claude-sonnet-4.5-20250929
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 500);

        // Test versioned model names with dots (as users would specify them)
        BigDecimal cost1 = CostService.calculateCost("claude-sonnet-4.5-20250929", "anthropic", usage, null);
        BigDecimal cost2 = CostService.calculateCost("claude-haiku-4.5-20251001", "anthropic", usage, null);

        // Both should resolve to their hyphenated equivalents and return non-zero costs
        assertThat(cost1).isGreaterThan(BigDecimal.ZERO);
        assertThat(cost2).isGreaterThan(BigDecimal.ZERO);
    }
}
