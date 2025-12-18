package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.comet.opik.domain.model.ModelPrice;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CostServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static Map<String, ModelPrice> originalModelPrices;

    @BeforeAll
    static void setup() throws Exception {
        // Inject tts-1 price using Reflection
        Field field = CostService.class.getDeclaredField("modelProviderPrices");
        field.setAccessible(true);
        
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        originalModelPrices = (Map<String, ModelPrice>) field.get(null);
        
        Map<String, ModelPrice> newPrices = new HashMap<>(originalModelPrices);
        
        ModelPrice ttsPrice = ModelPrice.builder()
                .inputPrice(new BigDecimal("0.000015"))
                .outputPrice(BigDecimal.ZERO)
                .cacheCreationInputTokenPrice(BigDecimal.ZERO)
                .cacheReadInputTokenPrice(BigDecimal.ZERO)
                .videoOutputPrice(BigDecimal.ZERO)
                .calculator(SpanCostCalculator::audioSpeechCost)
                .build();

        newPrices.put("tts-1", ttsPrice);
        
        field.set(null, Collections.unmodifiableMap(newPrices));
    }
    
    @AfterAll
    static void tearDown() throws Exception {
        if (originalModelPrices != null) {
            Field field = CostService.class.getDeclaredField("modelProviderPrices");
            field.setAccessible(true);
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            
            field.set(null, originalModelPrices);
        }
    }


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

    @ParameterizedTest
    @MethodSource("provideUseCasesForCostCalculation")
    void calculateCost_shouldCalculateCorrectly(String modelName, String provider, Map<String, Integer> usage,
            BigDecimal expectedCost) {
        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideUseCasesForCostCalculation() {
        return Stream.of(
                // Video generation positive
                Arguments.of("sora-2", "openai", Map.of("video_duration_seconds", 4), new BigDecimal("0.4")),
                // Video generation zero duration
                Arguments.of("sora-2", "openai", Map.of("video_duration_seconds", 0), BigDecimal.ZERO),
                // Video generation negative duration
                Arguments.of("sora-2", "openai", Map.of("video_duration_seconds", -5), BigDecimal.ZERO),
                // Video generation unknown model (no price)
                Arguments.of("unknown-video-model", "openai", Map.of("video_duration_seconds", 4), BigDecimal.ZERO),

                // Audio speech positive
                Arguments.of("tts-1", "openai", Map.of("prompt_tokens", 1000), new BigDecimal("0.015")),
                
                // Audio speech zero chars
                Arguments.of("tts-1", "openai", Map.of("prompt_tokens", 0), BigDecimal.ZERO),
                // Audio speech negative chars
                Arguments.of("tts-1", "openai", Map.of("prompt_tokens", -10), BigDecimal.ZERO)
        );
    }

}
