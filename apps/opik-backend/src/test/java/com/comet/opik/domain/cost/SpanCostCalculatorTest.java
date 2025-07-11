package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SpanCostCalculatorTest {

    private static final BigDecimal INPUT_PRICE = new BigDecimal("0.0001");
    private static final BigDecimal OUTPUT_PRICE = new BigDecimal("0.0002");
    private static final BigDecimal CACHE_CREATION_PRICE = new BigDecimal("0.00005");
    private static final BigDecimal CACHE_READ_PRICE = new BigDecimal("0.00001");
    private static final BigDecimal AUDIO_INPUT_PRICE = new BigDecimal("0.001");
    private static final BigDecimal AUDIO_OUTPUT_PRICE = new BigDecimal("0.002");
    private static final BigDecimal IMAGE_INPUT_PRICE = new BigDecimal("0.01");
    private static final BigDecimal VIDEO_INPUT_PRICE = new BigDecimal("0.1");
    private static final BigDecimal AUDIO_PER_SECOND_PRICE = new BigDecimal("0.0001");
    private static final BigDecimal VIDEO_PER_SECOND_PRICE = new BigDecimal("0.001");

    private ModelPrice createTestModelPrice() {
        return new ModelPrice(
                INPUT_PRICE,
                OUTPUT_PRICE,
                CACHE_CREATION_PRICE,
                CACHE_READ_PRICE,
                AUDIO_INPUT_PRICE,
                AUDIO_OUTPUT_PRICE,
                IMAGE_INPUT_PRICE,
                VIDEO_INPUT_PRICE,
                AUDIO_PER_SECOND_PRICE,
                VIDEO_PER_SECOND_PRICE,
                SpanCostCalculator::textGenerationCost);
    }

    @Test
    void testTextGenerationCost() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50);

        BigDecimal result = SpanCostCalculator.textGenerationCost(modelPrice, usage);

        BigDecimal expected = INPUT_PRICE.multiply(BigDecimal.valueOf(100))
                .add(OUTPUT_PRICE.multiply(BigDecimal.valueOf(50)));
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testMultimodalCost() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "audio_input_tokens", 200,
                "audio_output_tokens", 150,
                "image_count", 2,
                "video_seconds", 30,
                "audio_seconds", 45);

        BigDecimal result = SpanCostCalculator.multimodalCost(modelPrice, usage);

        BigDecimal expected = INPUT_PRICE.multiply(BigDecimal.valueOf(100))
                .add(OUTPUT_PRICE.multiply(BigDecimal.valueOf(50)))
                .add(AUDIO_INPUT_PRICE.multiply(BigDecimal.valueOf(200)))
                .add(AUDIO_OUTPUT_PRICE.multiply(BigDecimal.valueOf(150)))
                .add(IMAGE_INPUT_PRICE.multiply(BigDecimal.valueOf(2)))
                .add(VIDEO_PER_SECOND_PRICE.multiply(BigDecimal.valueOf(30)))
                .add(AUDIO_PER_SECOND_PRICE.multiply(BigDecimal.valueOf(45)));

        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testMultimodalCostWithCache() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "cached_tokens", 20,
                "cache_creation_input_tokens", 10,
                "audio_input_tokens", 200,
                "audio_output_tokens", 150,
                "image_count", 2,
                "video_seconds", 30,
                "audio_seconds", 45);

        BigDecimal result = SpanCostCalculator.multimodalCostWithCache(modelPrice, usage);

        BigDecimal expected = INPUT_PRICE.multiply(BigDecimal.valueOf(80)) // 100 - 20 cached
                .add(OUTPUT_PRICE.multiply(BigDecimal.valueOf(50)))
                .add(CACHE_READ_PRICE.multiply(BigDecimal.valueOf(20)))
                .add(CACHE_CREATION_PRICE.multiply(BigDecimal.valueOf(10)))
                .add(AUDIO_INPUT_PRICE.multiply(BigDecimal.valueOf(200)))
                .add(AUDIO_OUTPUT_PRICE.multiply(BigDecimal.valueOf(150)))
                .add(IMAGE_INPUT_PRICE.multiply(BigDecimal.valueOf(2)))
                .add(VIDEO_PER_SECOND_PRICE.multiply(BigDecimal.valueOf(30)))
                .add(AUDIO_PER_SECOND_PRICE.multiply(BigDecimal.valueOf(45)));

        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testAudioCost() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of(
                "audio_input_tokens", 200,
                "audio_output_tokens", 150,
                "audio_seconds", 45);

        BigDecimal result = SpanCostCalculator.audioCost(modelPrice, usage);

        BigDecimal expected = AUDIO_INPUT_PRICE.multiply(BigDecimal.valueOf(200))
                .add(AUDIO_OUTPUT_PRICE.multiply(BigDecimal.valueOf(150)))
                .add(AUDIO_PER_SECOND_PRICE.multiply(BigDecimal.valueOf(45)));

        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testImageCost() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of("image_count", 3);

        BigDecimal result = SpanCostCalculator.imageCost(modelPrice, usage);

        BigDecimal expected = IMAGE_INPUT_PRICE.multiply(BigDecimal.valueOf(3));
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testVideoCost() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of("video_seconds", 120);

        BigDecimal result = SpanCostCalculator.videoCost(modelPrice, usage);

        BigDecimal expected = VIDEO_PER_SECOND_PRICE.multiply(BigDecimal.valueOf(120));
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testDefaultCost() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = new HashMap<>();

        BigDecimal result = SpanCostCalculator.defaultCost(modelPrice, usage);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest
    @MethodSource("multimodalCostTestData")
    void testMultimodalCostWithVariousInputs(Map<String, Integer> usage, BigDecimal expectedCost) {
        ModelPrice modelPrice = createTestModelPrice();
        BigDecimal result = SpanCostCalculator.multimodalCost(modelPrice, usage);
        assertThat(result).isEqualByComparingTo(expectedCost);
    }

    static Stream<Arguments> multimodalCostTestData() {
        return Stream.of(
                Arguments.of(
                        Map.of("prompt_tokens", 10, "completion_tokens", 5),
                        INPUT_PRICE.multiply(BigDecimal.valueOf(10)).add(OUTPUT_PRICE.multiply(BigDecimal.valueOf(5)))),
                Arguments.of(
                        Map.of("audio_input_tokens", 100),
                        AUDIO_INPUT_PRICE.multiply(BigDecimal.valueOf(100))),
                Arguments.of(
                        Map.of("image_count", 1),
                        IMAGE_INPUT_PRICE.multiply(BigDecimal.valueOf(1))),
                Arguments.of(
                        Map.of("video_seconds", 10),
                        VIDEO_PER_SECOND_PRICE.multiply(BigDecimal.valueOf(10))),
                Arguments.of(
                        Map.of("audio_seconds", 5),
                        AUDIO_PER_SECOND_PRICE.multiply(BigDecimal.valueOf(5))),
                Arguments.of(
                        Map.of(),
                        BigDecimal.ZERO));
    }

    @Test
    void testTextGenerationWithCacheCostOpenAI() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of(
                "original_usage.prompt_tokens", 100,
                "original_usage.completion_tokens", 50,
                "original_usage.prompt_tokens_details.cached_tokens", 20);

        BigDecimal result = SpanCostCalculator.textGenerationWithCacheCostOpenAI(modelPrice, usage);

        BigDecimal expected = INPUT_PRICE.multiply(BigDecimal.valueOf(80)) // 100 - 20 cached
                .add(OUTPUT_PRICE.multiply(BigDecimal.valueOf(50)))
                .add(CACHE_READ_PRICE.multiply(BigDecimal.valueOf(20)));

        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testTextGenerationWithCacheCostAnthropic() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 100,
                "original_usage.output_tokens", 50,
                "original_usage.cache_read_input_tokens", 20,
                "original_usage.cache_creation_input_tokens", 10);

        BigDecimal result = SpanCostCalculator.textGenerationWithCacheCostAnthropic(modelPrice, usage);

        BigDecimal expected = INPUT_PRICE.multiply(BigDecimal.valueOf(100))
                .add(OUTPUT_PRICE.multiply(BigDecimal.valueOf(50)))
                .add(CACHE_READ_PRICE.multiply(BigDecimal.valueOf(20)))
                .add(CACHE_CREATION_PRICE.multiply(BigDecimal.valueOf(10)));

        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testMultimodalCostWithMissingUsageFields() {
        ModelPrice modelPrice = createTestModelPrice();
        Map<String, Integer> usage = Map.of("prompt_tokens", 100);

        BigDecimal result = SpanCostCalculator.multimodalCost(modelPrice, usage);

        BigDecimal expected = INPUT_PRICE.multiply(BigDecimal.valueOf(100));
        assertThat(result).isEqualByComparingTo(expected);
    }

    @Test
    void testMultimodalCostWithZeroPrices() {
        ModelPrice modelPrice = new ModelPrice(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                SpanCostCalculator::multimodalCost);

        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 50,
                "audio_input_tokens", 200,
                "image_count", 2);

        BigDecimal result = SpanCostCalculator.multimodalCost(modelPrice, usage);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }
}