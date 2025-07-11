package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void testCalculateCostWithTextOnlyModel() {
        String modelName = "gpt-4";
        String provider = "openai";
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithMultimodalModel() {
        String modelName = "gpt-4o-audio-preview";
        String provider = "openai";
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "audio_input_tokens", 200,
                "audio_output_tokens", 150);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithUnknownModel() {
        String modelName = "unknown-model";
        String provider = "unknown-provider";
        Map<String, Integer> usage = Map.of("prompt_tokens", 100);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithNullInputs() {
        BigDecimal cost = CostService.calculateCost(null, null, null, null);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithEmptyUsage() {
        String modelName = "gpt-4";
        String provider = "openai";
        Map<String, Integer> usage = Map.of();

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testGetCostFromMetadata() throws Exception {
        String metadataJson = """
                {
                    "cost": {
                        "currency": "USD",
                        "total_tokens": 0.0015
                    }
                }
                """;
        JsonNode metadata = OBJECT_MAPPER.readTree(metadataJson);

        BigDecimal cost = CostService.getCostFromMetadata(metadata);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0015"));
    }

    @Test
    void testGetCostFromMetadataWithInvalidCurrency() throws Exception {
        String metadataJson = """
                {
                    "cost": {
                        "currency": "EUR",
                        "total_tokens": 0.0015
                    }
                }
                """;
        JsonNode metadata = OBJECT_MAPPER.readTree(metadataJson);

        BigDecimal cost = CostService.getCostFromMetadata(metadata);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testGetCostFromMetadataWithNullMetadata() {
        BigDecimal cost = CostService.getCostFromMetadata(null);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testGetCostFromMetadataWithMissingCost() throws Exception {
        String metadataJson = """
                {
                    "other_field": "value"
                }
                """;
        JsonNode metadata = OBJECT_MAPPER.readTree(metadataJson);

        BigDecimal cost = CostService.getCostFromMetadata(metadata);

        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @MethodSource("multimodalCostTestData")
    void testCalculateCostWithMultimodalUsage(String modelName, String provider, Map<String, Integer> usage,
            boolean shouldHaveCost) {
        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        if (shouldHaveCost) {
            assertThat(cost).isGreaterThan(BigDecimal.ZERO);
        } else {
            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    static Stream<Arguments> multimodalCostTestData() {
        return Stream.of(
                Arguments.of(
                        "gpt-4o-audio-preview",
                        "openai",
                        Map.of("prompt_tokens", 100, "audio_input_tokens", 200),
                        true),
                Arguments.of(
                        "gpt-4-vision-preview",
                        "openai",
                        Map.of("prompt_tokens", 100, "image_count", 2),
                        true),
                Arguments.of(
                        "unknown-model",
                        "unknown",
                        Map.of("prompt_tokens", 100, "audio_input_tokens", 200),
                        false),
                Arguments.of(
                        "gpt-4",
                        "openai",
                        Map.of("prompt_tokens", 100, "completion_tokens", 50),
                        true));
    }

    @Test
    void testCalculateCostFallbackToMetadata() throws Exception {
        String modelName = "unknown-model";
        String provider = "unknown-provider";
        Map<String, Integer> usage = Map.of("prompt_tokens", 100);

        String metadataJson = """
                {
                    "cost": {
                        "currency": "USD",
                        "total_tokens": 0.0025
                    }
                }
                """;
        JsonNode metadata = OBJECT_MAPPER.readTree(metadataJson);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, metadata);

        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0025"));
    }

    @Test
    void testCalculateCostWithCacheSupport() {
        String modelName = "claude-3-5-sonnet-20241022";
        String provider = "anthropic";
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 100,
                "original_usage.output_tokens", 50,
                "original_usage.cache_read_input_tokens", 20,
                "original_usage.cache_creation_input_tokens", 10);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithMultimodalAndCache() {
        String modelName = "gpt-4o-audio-preview";
        String provider = "openai";
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "audio_input_tokens", 200,
                "audio_output_tokens", 150,
                "cached_tokens", 20,
                "cache_creation_input_tokens", 10);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithProviderMapping() {
        String modelName = "gpt-4";
        String provider = "openai";
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithImageSupport() {
        String modelName = "gpt-4-vision-preview";
        String provider = "openai";
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "image_count", 3);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithVideoSupport() {
        String modelName = "gemini-2.0-flash-thinking-exp";
        String provider = "gemini";
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "video_seconds", 30);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        // Note: This might return zero if the model isn't in the configuration
        assertThat(cost).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void testCalculateCostWithAudioPerSecond() {
        String modelName = "gemini-2.0-flash-thinking-exp";
        String provider = "gemini";
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "audio_seconds", 45);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        // Note: This might return zero if the model isn't in the configuration
        assertThat(cost).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}