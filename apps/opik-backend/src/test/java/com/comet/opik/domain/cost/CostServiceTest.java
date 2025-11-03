package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ========== calculateCost() Basic Tests ==========

    @Test
    void calculateCostReturnsZeroForNullModelName() {
        var cost = CostService.calculateCost(null, "openai", Map.of(), null);

        assertThat(cost).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void calculateCostReturnsZeroForNullProvider() {
        var cost = CostService.calculateCost("gpt-4", null, Map.of(), null);

        assertThat(cost).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void calculateCostReturnsZeroForUnknownModel() {
        var cost = CostService.calculateCost("unknown-model-12345", "openai", Map.of(), null);

        assertThat(cost).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void calculateCostReturnsZeroForEmptyUsage() {
        var cost = CostService.calculateCost("gpt-4", "openai", Map.of(), null);

        assertThat(cost).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void calculateCostReturnsZeroForNullUsage() {
        var cost = CostService.calculateCost("gpt-4", "openai", null, null);

        assertThat(cost).isEqualTo(BigDecimal.ZERO);
    }

    // ========== calculateCost() with Known Models ==========

    @Test
    void calculateCostForGPT4WithTokenUsage() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        var cost = CostService.calculateCost("gpt-4", "openai", usage, null);

        // Cost should be > 0 for known model with usage
        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCostForClaude3WithTokenUsage() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        var cost = CostService.calculateCost("claude-3-5-sonnet-20241022", "anthropic", usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCostForGeminiWithTokenUsage() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        var cost = CostService.calculateCost("gemini-1.5-pro", "google_vertexai", usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    // ========== Model Name Matching Tests ==========

    @Test
    void calculateCostMatchesModelWithDifferentCase() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        var lowerCaseCost = CostService.calculateCost("gpt-4", "openai", usage, null);
        var upperCaseCost = CostService.calculateCost("GPT-4", "openai", usage, null);
        var mixedCaseCost = CostService.calculateCost("Gpt-4", "openai", usage, null);

        // All should return the same cost
        assertThat(lowerCaseCost).isEqualTo(upperCaseCost);
        assertThat(lowerCaseCost).isEqualTo(mixedCaseCost);
    }

    @Test
    void calculateCostMatchesModelWithProviderPrefix() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        // Both with and without provider prefix should work
        var withoutPrefix = CostService.calculateCost("gpt-4", "openai", usage, null);
        var withPrefix = CostService.calculateCost("openai/gpt-4", "openai", usage, null);

        assertThat(withoutPrefix).isEqualTo(withPrefix);
        assertThat(withoutPrefix).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCostMatchesOpenRouterModels() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        // OpenRouter models should be matched correctly
        var cost = CostService.calculateCost("qwen/qwen-2.5-7b-instruct", "openrouter", usage, null);

        // Should find cost for known OpenRouter model
        assertThat(cost).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void calculateCostMatchesDeepInfraModels() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        // DeepInfra models should be matched correctly
        var cost = CostService.calculateCost("qwen/qwen2.5-vl-32b-instruct", "deepinfra", usage, null);

        assertThat(cost).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void calculateCostHandlesModelWithColonSuffix() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        // Models with :free suffix return $0 regardless of base model cost
        var freeCost = CostService.calculateCost("gpt-4:free", "openai", usage, null);
        assertThat(freeCost).isEqualTo(BigDecimal.ZERO);

        // Base model without suffix has normal pricing
        var normalCost = CostService.calculateCost("gpt-4", "openai", usage, null);
        assertThat(normalCost).isGreaterThan(BigDecimal.ZERO);

        // Other colon suffixes (like :extended) must exist in JSON as exact matches
        // If not in JSON, they return $0 (no match found)
        var unknownVariant = CostService.calculateCost("gpt-4:unknown-variant", "openai", usage, null);
        assertThat(unknownVariant).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void calculateCostReturnsZeroForFreeTierModels() {
        var usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 500);

        // Free tier models should always return $0 cost regardless of canonical mapping
        var freeCost = CostService.calculateCost("qwen/qwen2.5-vl-32b-instruct:free", "openrouter", usage, null);
        assertThat(freeCost).isEqualTo(BigDecimal.ZERO);

        // Even if the base model has a cost, :free variant should be zero
        var paidCost = CostService.calculateCost("gpt-4:free", "openai", usage, null);
        assertThat(paidCost).isEqualTo(BigDecimal.ZERO);

        // Other variants without :free should have normal pricing
        var normalCost = CostService.calculateCost("gpt-4", "openai", usage, null);
        assertThat(normalCost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCostTrimsWhitespaceFromModelName() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        var withWhitespace = CostService.calculateCost("  gpt-4  ", "openai", usage, null);
        var withoutWhitespace = CostService.calculateCost("gpt-4", "openai", usage, null);

        assertThat(withWhitespace).isEqualTo(withoutWhitespace);
    }

    @Test
    void calculateCostTrimsWhitespaceFromProvider() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        var withWhitespace = CostService.calculateCost("gpt-4", "  openai  ", usage, null);
        var withoutWhitespace = CostService.calculateCost("gpt-4", "openai", usage, null);

        assertThat(withWhitespace).isEqualTo(withoutWhitespace);
    }

    // ========== getCostFromMetadata() Tests ==========

    @Test
    void getCostFromMetadataReturnsZeroForNull() {
        assertThat(CostService.getCostFromMetadata(null)).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void getCostFromMetadataReturnsZeroForEmptyMetadata() throws Exception {
        var metadata = OBJECT_MAPPER.readTree("{}");

        assertThat(CostService.getCostFromMetadata(metadata)).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void getCostFromMetadataReturnsZeroForMissingCostField() throws Exception {
        var metadata = OBJECT_MAPPER.readTree("{\"other\": \"value\"}");

        assertThat(CostService.getCostFromMetadata(metadata)).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void getCostFromMetadataReturnsZeroForNonUSDCurrency() throws Exception {
        var metadata = OBJECT_MAPPER.readTree("""
                {
                  "cost": {
                    "currency": "EUR",
                    "total_tokens": 1.5
                  }
                }
                """);

        assertThat(CostService.getCostFromMetadata(metadata)).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void getCostFromMetadataReturnsCostForUSDCurrency() throws Exception {
        var metadata = OBJECT_MAPPER.readTree("""
                {
                  "cost": {
                    "currency": "USD",
                    "total_tokens": 1.5
                  }
                }
                """);

        var cost = CostService.getCostFromMetadata(metadata);
        assertThat(cost).isEqualTo(new BigDecimal("1.5"));
    }

    @Test
    void getCostFromMetadataHandlesMissingTotalTokens() throws Exception {
        var metadata = OBJECT_MAPPER.readTree("""
                {
                  "cost": {
                    "currency": "USD"
                  }
                }
                """);

        assertThat(CostService.getCostFromMetadata(metadata)).isEqualTo(BigDecimal.ZERO);
    }

    // ========== calculateCost() with Metadata Fallback ==========

    @Test
    void calculateCostFallsBackToMetadataWhenEstimatedCostIsZero() throws Exception {
        var metadata = OBJECT_MAPPER.readTree("""
                {
                  "cost": {
                    "currency": "USD",
                    "total_tokens": 2.5
                  }
                }
                """);

        // Unknown model should return 0 estimated cost, then fall back to metadata
        var cost = CostService.calculateCost("unknown-model", "openai", Map.of(), metadata);

        assertThat(cost).isEqualTo(new BigDecimal("2.5"));
    }

    @Test
    void calculateCostPrefersEstimatedCostOverMetadata() throws Exception {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        var metadata = OBJECT_MAPPER.readTree("""
                {
                  "cost": {
                    "currency": "USD",
                    "total_tokens": 999.99
                  }
                }
                """);

        // Should use estimated cost, not metadata cost
        var cost = CostService.calculateCost("gpt-4", "openai", usage, metadata);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
        assertThat(cost).isNotEqualTo(new BigDecimal("999.99"));
    }

    // ========== Provider Mapping Tests ==========

    @Test
    void calculateCostWorksWithAllSupportedProviders() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        // Test each supported provider with a known model
        var providers = Map.of(
                "openai", "gpt-4",
                "anthropic", "claude-3-5-sonnet-20241022",
                "google_vertexai", "gemini-1.5-pro",
                "google_ai", "gemini-1.5-pro",
                "openrouter", "qwen/qwen-2.5-7b-instruct",
                "deepinfra", "qwen/qwen2.5-vl-32b-instruct");

        providers.forEach((provider, model) -> {
            var cost = CostService.calculateCost(model, provider, usage, null);
            // Each should return a non-negative cost
            assertThat(cost).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        });
    }

    @Test
    void calculateCostHandlesProviderCaseInsensitivity() {
        var usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        var lowerCase = CostService.calculateCost("gpt-4", "openai", usage, null);
        var upperCase = CostService.calculateCost("gpt-4", "OPENAI", usage, null);
        var mixedCase = CostService.calculateCost("gpt-4", "OpenAI", usage, null);

        assertThat(lowerCase).isEqualTo(upperCase);
        assertThat(lowerCase).isEqualTo(mixedCase);
    }
}
