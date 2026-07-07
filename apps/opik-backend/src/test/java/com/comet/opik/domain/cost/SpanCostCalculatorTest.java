package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanCostCalculatorTest {

    @Test
    void videoGenerationCostReturnsZeroWhenPriceIsZero() {
        ModelPrice modelPrice = ModelPrice.defaultBuilder().build();

        BigDecimal cost = SpanCostCalculator.videoGenerationCost(modelPrice, Map.of("video_duration_seconds", 10));

        assertThat(cost).isZero();
    }

    @Test
    void videoGenerationCostValidatesArguments() {
        assertThatThrownBy(() -> SpanCostCalculator.videoGenerationCost(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .inputPrice(BigDecimal.ONE)
                .outputPrice(BigDecimal.ONE)
                .cacheCreationInputTokenPrice(BigDecimal.ONE)
                .cacheReadInputTokenPrice(BigDecimal.ONE)
                .videoOutputPrice(BigDecimal.ONE)
                .calculator(SpanCostCalculator::textGenerationCost)
                .build();
        assertThatThrownBy(() -> SpanCostCalculator.videoGenerationCost(modelPrice, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void videoGenerationCostMultipliesDurationAndPrice() {
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .videoOutputPrice(new BigDecimal("0.5"))
                .build();

        BigDecimal cost = SpanCostCalculator.videoGenerationCost(modelPrice, Map.of("video_duration_seconds", 2));

        // 2 seconds * 0.5 = 1.0
        assertThat(cost).isEqualByComparingTo("1.0");
    }

    // --- Audio Speech Cost Tests ---

    @ParameterizedTest
    @MethodSource("provideAudioSpeechZeroCostCases")
    void audioSpeechCostReturnsZero(String pricePerChar, Map<String, Integer> usage) {
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .audioInputCharacterPrice(new BigDecimal(pricePerChar))
                .build();

        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice, usage);

        assertThat(cost).isZero();
    }

    private static Stream<Arguments> provideAudioSpeechZeroCostCases() {
        return Stream.of(
                // Zero price per character → zero cost
                Arguments.of("0", Map.of("input_characters", 100)),
                // Non-zero price but no characters in usage → zero cost
                Arguments.of("0.000015", Map.of()));
    }

    @Test
    void audioSpeechCostValidatesArguments() {
        assertThatThrownBy(() -> SpanCostCalculator.audioSpeechCost(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .audioInputCharacterPrice(new BigDecimal("0.000015"))
                .build();
        assertThatThrownBy(() -> SpanCostCalculator.audioSpeechCost(modelPrice, null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @MethodSource("provideAudioSpeechCostCases")
    void audioSpeechCostMultipliesCharactersAndPrice(String pricePerChar, int inputCharacters, String expectedCost) {
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .audioInputCharacterPrice(new BigDecimal(pricePerChar))
                .build();

        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice, Map.of("input_characters", inputCharacters));

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideAudioSpeechCostCases() {
        return Stream.of(
                // tts-1: $0.000015 per character, 1000 characters → $0.015
                Arguments.of("0.000015", 1000, "0.015"),
                // tts-1-hd: $0.000030 per character, 500 characters → $0.015
                Arguments.of("0.000030", 500, "0.015"));
    }

    @Test
    void audioSpeechCostUsesOriginalUsagePrefix() {
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .audioInputCharacterPrice(new BigDecimal("0.000015"))
                .build();

        // SDK 1.6.0+ sends usage with original_usage. prefix
        BigDecimal cost = SpanCostCalculator.audioSpeechCost(modelPrice,
                Map.of("original_usage.input_characters", 1000));

        // 1000 characters * 0.000015 = 0.015
        assertThat(cost).isEqualByComparingTo("0.015");
    }

    // --- Cache Cost OTel Fallback Tests ---

    @ParameterizedTest(name = "{1}")
    @MethodSource("provideOpenAICacheCostCases")
    void textGenerationWithCacheCostOpenAI(Map<String, Integer> usage, String description, String expectedCost) {
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .inputPrice(new BigDecimal("0.01"))
                .outputPrice(new BigDecimal("0.02"))
                .cacheReadInputTokenPrice(new BigDecimal("0.005"))
                .calculator(SpanCostCalculator::textGenerationWithCacheCostOpenAI)
                .build();

        BigDecimal cost = SpanCostCalculator.textGenerationWithCacheCostOpenAI(modelPrice, usage);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideOpenAICacheCostCases() {
        return Stream.of(
                // OTel key: prompt=1000, cache_read=200 → non-cached input=800
                // 800*0.01 + 100*0.02 + 200*0.005 = 8.00 + 2.00 + 1.00 = 11.00
                Arguments.of(
                        Map.of("prompt_tokens", 1000, "completion_tokens", 100, "cache_read_input_tokens", 200),
                        "OTel cache_read_input_tokens key",
                        "11.00"),
                // original_usage key takes precedence: cached=300 overrides cache_read_input_tokens=200
                // 700*0.01 + 100*0.02 + 300*0.005 = 7.00 + 2.00 + 1.50 = 10.50
                Arguments.of(
                        Map.of("original_usage.prompt_tokens", 1000, "original_usage.completion_tokens", 100,
                                "original_usage.prompt_tokens_details.cached_tokens", 300,
                                "cache_read_input_tokens", 200),
                        "original_usage key takes precedence over OTel key",
                        "10.50"),
                // OpenAI Responses API reports cached tokens under input_tokens_details.cached_tokens
                // non-cached input = 1000 - 300 = 700 -> 700*0.01 + 100*0.02 + 300*0.005 = 10.50
                Arguments.of(
                        Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                                "original_usage.input_tokens_details.cached_tokens", 300),
                        "Responses API input_tokens_details.cached_tokens key",
                        "10.50"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("provideGoogleCacheCostCases")
    void textGenerationWithCacheCostGoogle(Map<String, Integer> usage, String description, String expectedCost) {
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .inputPrice(new BigDecimal("0.01"))
                .outputPrice(new BigDecimal("0.02"))
                .cacheReadInputTokenPrice(new BigDecimal("0.005"))
                .calculator(SpanCostCalculator::textGenerationWithCacheCostGoogle)
                .build();

        BigDecimal cost = SpanCostCalculator.textGenerationWithCacheCostGoogle(modelPrice, usage);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideGoogleCacheCostCases() {
        return Stream.of(
                // Gemini reports cached tokens under cached_content_token_count, included in prompt_token_count
                // non-cached input = 1000 - 300 = 700 -> 700*0.01 + 100*0.02 + 300*0.005 = 7.00 + 2.00 + 1.50 = 10.50
                Arguments.of(
                        Map.of("original_usage.prompt_token_count", 1000, "completion_tokens", 100,
                                "original_usage.cached_content_token_count", 300),
                        "Gemini cached_content_token_count key",
                        "10.50"),
                // OTel fallback: cache_read_input_tokens key, prompt=1000, cache_read=200 -> non-cached input=800
                // 800*0.01 + 100*0.02 + 200*0.005 = 8.00 + 2.00 + 1.00 = 11.00
                Arguments.of(
                        Map.of("prompt_tokens", 1000, "completion_tokens", 100, "cache_read_input_tokens", 200),
                        "OTel cache_read_input_tokens key",
                        "11.00"));
    }

    /**
     * Covers the audio-token branches added to
     * {@link SpanCostCalculator#textGenerationWithCacheCostOpenAI}. OpenAI realtime models
     * (e.g. {@code gpt-4o-realtime-preview}, {@code gpt-realtime}) publish both
     * {@code cache_read_input_token_cost} and {@code input_cost_per_audio_token}/
     * {@code output_cost_per_audio_token}; without this split the cache calculator billed all
     * audio tokens at the standard text rate.
     * <ul>
     *   <li>When the model has non-zero audio input/output rates, the audio portions are
     *       subtracted from the cache-adjusted input and from the completion buckets and billed
     *       at the audio rates.</li>
     *   <li>When the model has no audio rates configured, any audio-token usage keys are
     *       ignored — every prompt token (minus cached) bills at the standard input rate.</li>
     * </ul>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("provideOpenAICacheAudioCases")
    void textGenerationWithCacheCostOpenAI_splitsAudioTokens(String description, ModelPrice modelPrice,
            Map<String, Integer> usage, String expectedCost) {
        BigDecimal cost = SpanCostCalculator.textGenerationWithCacheCostOpenAI(modelPrice, usage);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideOpenAICacheAudioCases() {
        ModelPrice withAudioRates = ModelPrice.defaultBuilder()
                .inputPrice(new BigDecimal("0.01"))
                .outputPrice(new BigDecimal("0.02"))
                .cacheReadInputTokenPrice(new BigDecimal("0.005"))
                .inputAudioTokenPrice(new BigDecimal("0.05"))
                .outputAudioTokenPrice(new BigDecimal("0.08"))
                .build();
        ModelPrice noAudioRates = ModelPrice.defaultBuilder()
                .inputPrice(new BigDecimal("0.01"))
                .outputPrice(new BigDecimal("0.02"))
                .cacheReadInputTokenPrice(new BigDecimal("0.005"))
                .build();
        return Stream.of(
                // audio rates configured: subtract cached then audio from input,
                // subtract audio from completion, bill each bucket at its own rate.
                // input bucket = 1000 - 200 cached - 300 audio = 500 -> 500 * 0.01 = 5.00
                // audio_in = 300 * 0.05 = 15.00
                // completion bucket = 500 - 100 audio = 400 -> 400 * 0.02 = 8.00
                // audio_out = 100 * 0.08 = 8.00
                // cache_read = 200 * 0.005 = 1.00
                // total = 5 + 15 + 8 + 8 + 1 = 37.00
                Arguments.of("audio rates set: realtime-style usage splits all four buckets",
                        withAudioRates,
                        Map.of("prompt_tokens", 1000, "completion_tokens", 500,
                                "cache_read_input_tokens", 200,
                                "prompt_tokens_details.audio_tokens", 300,
                                "completion_tokens_details.audio_tokens", 100),
                        "37.00"),
                // audio rates not configured: audio-token keys in usage are ignored
                // input = 1000 - 200 cached = 800 -> 800 * 0.01 = 8.00
                // completion = 100 -> 100 * 0.02 = 2.00
                // cache_read = 200 * 0.005 = 1.00
                // total = 8 + 2 + 1 = 11.00
                Arguments.of("audio rates unset: audio_tokens keys are ignored",
                        noAudioRates,
                        Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                                "cache_read_input_tokens", 200,
                                "prompt_tokens_details.audio_tokens", 300,
                                "completion_tokens_details.audio_tokens", 50),
                        "11.00"),
                // original_usage.* key path (SDK 1.6.0+): same expected math as the bare-key
                // case above; guarantees the prefixed-key branch is exercised in regressions.
                // 1000 - 200 cached - 300 audio = 500 input; 500 - 100 audio = 400 output
                // 500*0.01 + 300*0.05 + 400*0.02 + 100*0.08 + 200*0.005 = 37.00
                Arguments.of("audio rates set: original_usage.* prefixed keys",
                        withAudioRates,
                        Map.of("original_usage.prompt_tokens", 1000,
                                "original_usage.completion_tokens", 500,
                                "original_usage.prompt_tokens_details.cached_tokens", 200,
                                "original_usage.prompt_tokens_details.audio_tokens", 300,
                                "original_usage.completion_tokens_details.audio_tokens", 100),
                        "37.00"),
                // OpenAI Realtime payloads report prompt_tokens_details.text_tokens explicitly.
                // When present, use it directly instead of substracting cached + audio from the
                // total — this avoids over-substraction when the API reports cached-audio
                // overlap (see baz-reviewer note on cached-audio double-charging).
                // text_tokens = 480 (API-reported: 20 cached-audio overlap tokens are NOT text)
                // 480*0.01 + 300*0.05 + 400*0.02 + 100*0.08 + 200*0.005 = 4.80 + 15 + 8 + 8 + 1
                // = 36.80  (vs 37.00 in the subtraction path — 20-token overlap reflected)
                Arguments.of("text_tokens present: preferred over subtraction",
                        withAudioRates,
                        Map.of("original_usage.prompt_tokens", 1000,
                                "original_usage.completion_tokens", 500,
                                "original_usage.prompt_tokens_details.cached_tokens", 200,
                                "original_usage.prompt_tokens_details.audio_tokens", 300,
                                "original_usage.prompt_tokens_details.text_tokens", 480,
                                "original_usage.completion_tokens_details.audio_tokens", 100,
                                "original_usage.completion_tokens_details.text_tokens", 400),
                        "36.80"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("provideAnthropicBedrockCacheCostCases")
    void textGenerationWithCacheCostUsesOtelCacheKeyFallback(
            BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator, String description) {
        ModelPrice modelPrice = ModelPrice.defaultBuilder()
                .inputPrice(new BigDecimal("0.01"))
                .outputPrice(new BigDecimal("0.02"))
                .cacheCreationInputTokenPrice(new BigDecimal("0.015"))
                .cacheReadInputTokenPrice(new BigDecimal("0.005"))
                .build();

        BigDecimal cost = calculator.apply(modelPrice,
                Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                        "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50));

        // input=1000*0.01 + output=100*0.02 + cacheCreation=50*0.015 + cacheRead=200*0.005
        // = 10.00 + 2.00 + 0.75 + 1.00 = 13.75
        assertThat(cost).isEqualByComparingTo("13.75");
    }

    private static Stream<Arguments> provideAnthropicBedrockCacheCostCases() {
        BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> anthropic = SpanCostCalculator::textGenerationWithCacheCostAnthropic;
        BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> bedrock = SpanCostCalculator::textGenerationWithCacheCostBedrock;
        return Stream.of(
                Arguments.of(anthropic, "Anthropic"),
                Arguments.of(bedrock, "Bedrock"));
    }
}
