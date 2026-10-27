package com.comet.opik.domain.cost;

import com.comet.opik.domain.token_usage.SpanUsageFactory;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.comet.opik.domain.token_usage.UsageCases.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanCostCalculatorTest {

    private static final ModelPrice CACHE_PRICES = ModelPrice.defaultBuilder()
            .inputPrice(new BigDecimal("0.01"))
            .outputPrice(new BigDecimal("0.02"))
            .cacheCreationInputTokenPrice(new BigDecimal("0.015"))
            .cacheReadInputTokenPrice(new BigDecimal("0.005"))
            .build();

    private static final ModelPrice NO_CACHE_PRICES = ModelPrice.defaultBuilder()
            .inputPrice(new BigDecimal("0.01"))
            .outputPrice(new BigDecimal("0.02"))
            .build();

    private static SpanUsage spanUsage(String provider, Object... keysAndValues) {
        return SpanUsageFactory.create(provider, usage(keysAndValues));
    }

    private static BigDecimal textCost(ModelPrice modelPrice, SpanUsage usage) {
        return SpanCostCalculator.textGenerationCost(modelPrice, usage);
    }

    @Nested
    class OpenAi {

        /**
         * Reasoning tokens are a subset of the completion and bill at {@code output_cost_per_reasoning_token} when the
         * model publishes it, so they come out of the standard-output bucket, and bill as output when it doesn't.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource
        void textGenerationCostSplitsReasoningTokens(String description, ModelPrice modelPrice, SpanUsage usage,
                String expectedCost) {
            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo(expectedCost);
        }

        static Stream<Arguments> textGenerationCostSplitsReasoningTokens() {
            ModelPrice withReasoningRate = NO_CACHE_PRICES.toBuilder()
                    .outputReasoningTokenPrice(new BigDecimal("0.003"))
                    .build();
            ModelPrice withReasoningAndAudioRates = withReasoningRate.toBuilder()
                    .outputAudioTokenPrice(new BigDecimal("0.08"))
                    .build();
            return Stream.of(
                    // input = 1000 * 0.01 = 10; standard = (500 - 200) * 0.02 = 6; reasoning = 200 * 0.003 = 0.6
                    Arguments.of("reasoning rate set", withReasoningRate,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.reasoning_tokens", 200),
                            "16.60"),
                    Arguments.of("reasoning rate set, usage logged by the SDK", withReasoningRate,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 500,
                                    "total_tokens", 1500, "original_usage.prompt_tokens", 1000,
                                    "original_usage.completion_tokens", 500,
                                    "original_usage.completion_tokens_details.reasoning_tokens", 200),
                            "16.60"),
                    // 10 + (500 - 100 - 200) * 0.02 + 100 * 0.08 + 200 * 0.003 = 10 + 4 + 8 + 0.6
                    Arguments.of("reasoning and audio rates set", withReasoningAndAudioRates,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.audio_tokens", 100,
                                    "completion_tokens_details.reasoning_tokens", 200),
                            "22.60"),
                    Arguments.of("reasoning rate unset: reasoning bills as output", NO_CACHE_PRICES,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.reasoning_tokens", 200),
                            "20.00"),
                    Arguments.of("reasoning rate set, no reasoning reported", withReasoningRate,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 500),
                            "20.00"),
                    // 10 + 0 + 500 * 0.003
                    Arguments.of("reasoning equal to the completion empties the standard bucket", withReasoningRate,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.reasoning_tokens", 500),
                            "11.50"),
                    // 10 + 100 * 0.003: never more reasoning than completion
                    Arguments.of("over-reported reasoning is capped at the completion", withReasoningRate,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 100,
                                    "completion_tokens_details.reasoning_tokens", 200),
                            "10.30"),
                    Arguments.of("negative reasoning counts as none", withReasoningRate,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.reasoning_tokens", -10),
                            "20.00"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void textGenerationCostBillsCachedTokensAtTheCacheReadRate(String description, SpanUsage usage,
                String expectedCost) {
            assertThat(textCost(CACHE_PRICES, usage)).isEqualByComparingTo(expectedCost);
        }

        static Stream<Arguments> textGenerationCostBillsCachedTokensAtTheCacheReadRate() {
            return Stream.of(
                    // (1000 - 200) * 0.01 + 100 * 0.02 + 200 * 0.005 = 8 + 2 + 1
                    Arguments.of("flat OTel cache key",
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 100,
                                    "cache_read_input_tokens", 200),
                            "11.00"),
                    // (1000 - 300) * 0.01 + 2 + 300 * 0.005 = 7 + 2 + 1.5; keys next to original_usage are ignored
                    Arguments.of("Chat Completions logged by the SDK",
                            spanUsage("openai", "cache_read_input_tokens", 200, "original_usage.prompt_tokens", 1000,
                                    "original_usage.completion_tokens", 100,
                                    "original_usage.prompt_tokens_details.cached_tokens", 300),
                            "10.50"),
                    Arguments.of("Responses API logged by the SDK",
                            spanUsage("openai", "original_usage.input_tokens", 1000,
                                    "original_usage.output_tokens", 100,
                                    "original_usage.input_tokens_details.cached_tokens", 300),
                            "10.50"),
                    Arguments.of("provider without a format of its own",
                            spanUsage("groq", "prompt_tokens", 1000, "completion_tokens", 100,
                                    "prompt_tokens_details.cached_tokens", 200),
                            "11.00"));
        }

        /** OpenRouter's Claude rates, against which the production spans below were billed on main. */
        @ParameterizedTest(name = "{0}")
        @MethodSource
        void textGenerationCostBillsCacheWritesAtTheWriteRate(String description, SpanUsage usage,
                String expectedCost) {
            var modelPrice = ModelPrice.defaultBuilder()
                    .inputPrice(new BigDecimal("0.000002"))
                    .outputPrice(new BigDecimal("0.00001"))
                    .cacheReadInputTokenPrice(new BigDecimal("0.0000002"))
                    .cacheCreationInputTokenPrice(new BigDecimal("0.0000025"))
                    .build();

            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo(expectedCost);
        }

        static Stream<Arguments> textGenerationCostBillsCacheWritesAtTheWriteRate() {
            return Stream.of(
                    // 4 * 0.000002 + 22,574 * 0.0000025 + 51 * 0.00001
                    Arguments.of("LiteLLM, whose prompt leaves the writes out",
                            spanUsage("openrouter", "prompt_tokens", 4, "completion_tokens", 51, "total_tokens", 55,
                                    "original_usage.prompt_tokens", 4, "original_usage.completion_tokens", 51,
                                    "original_usage.total_tokens", 55,
                                    "original_usage.cache_creation_input_tokens", 22574),
                            "0.056953"),
                    // 1 * 0.000002 + 66,468 * 0.0000025 + 12,036 * 0.0000002 + 1,540 * 0.00001
                    Arguments.of("flat detail leaves, whose prompt includes the reads and the writes",
                            spanUsage("openrouter", "prompt_tokens", 78505, "completion_tokens", 1540,
                                    "total_tokens", 80045, "cache_creation_input_tokens", 66468,
                                    "cache_read_input_tokens", 12036, "cached_input_tokens", 12036,
                                    "reasoning_tokens", 1152, "prompt_tokens_details.audio_tokens", 0,
                                    "completion_tokens_details.audio_tokens", 0),
                            "0.1839792"));
        }

        // A reported text count is used as sent; a negative one bills no text instead of failing the estimate.
        @Test
        void textGenerationCostTreatsANegativeReportedTextCountAsNone() {
            var modelPrice = NO_CACHE_PRICES.toBuilder().outputReasoningTokenPrice(new BigDecimal("0.003")).build();
            var usage = spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 100,
                    "completion_tokens_details.text_tokens", -5, "completion_tokens_details.reasoning_tokens", 10);

            // 1000 * 0.01; no text or reasoning completion tokens
            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo("10.00");
        }

        @Test
        void textGenerationCostBillsCachedTokensAsInputWithoutACacheRate() {
            var usage = spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 100,
                    "prompt_tokens_details.cached_tokens", 200);

            assertThat(textCost(NO_CACHE_PRICES, usage)).isEqualByComparingTo("12.00");
        }

        /**
         * Realtime models publish audio rates next to the cache rate. Audio comes out of the text buckets only when it
         * has a rate of its own, and a reported text count wins over subtracting, since cached and audio can overlap.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource
        void textGenerationCostSplitsAudioTokens(String description, ModelPrice modelPrice, SpanUsage usage,
                String expectedCost) {
            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo(expectedCost);
        }

        static Stream<Arguments> textGenerationCostSplitsAudioTokens() {
            ModelPrice withAudioRates = CACHE_PRICES.toBuilder()
                    .inputAudioTokenPrice(new BigDecimal("0.05"))
                    .outputAudioTokenPrice(new BigDecimal("0.08"))
                    .build();
            return Stream.of(
                    // (1000 - 200 - 300) * 0.01 + 300 * 0.05 + (500 - 100) * 0.02 + 100 * 0.08 + 200 * 0.005
                    // = 5 + 15 + 8 + 8 + 1
                    Arguments.of("audio rates set", withAudioRates,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 500,
                                    "cache_read_input_tokens", 200, "prompt_tokens_details.audio_tokens", 300,
                                    "completion_tokens_details.audio_tokens", 100),
                            "37.00"),
                    // (1000 - 200) * 0.01 + 100 * 0.02 + 200 * 0.005 = 8 + 2 + 1
                    Arguments.of("audio rates unset: audio bills as text", CACHE_PRICES,
                            spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 100,
                                    "cache_read_input_tokens", 200, "prompt_tokens_details.audio_tokens", 300,
                                    "completion_tokens_details.audio_tokens", 50),
                            "11.00"),
                    Arguments.of("audio rates set, usage logged by the SDK", withAudioRates,
                            spanUsage("openai", "original_usage.prompt_tokens", 1000,
                                    "original_usage.completion_tokens", 500,
                                    "original_usage.prompt_tokens_details.cached_tokens", 200,
                                    "original_usage.prompt_tokens_details.audio_tokens", 300,
                                    "original_usage.completion_tokens_details.audio_tokens", 100),
                            "37.00"),
                    // 480 * 0.01 + 15 + 400 * 0.02 + 8 + 1: the reported text count reflects a 20-token overlap
                    Arguments.of("reported text tokens win over subtracting", withAudioRates,
                            spanUsage("openai", "original_usage.prompt_tokens", 1000,
                                    "original_usage.completion_tokens", 500,
                                    "original_usage.prompt_tokens_details.cached_tokens", 200,
                                    "original_usage.prompt_tokens_details.audio_tokens", 300,
                                    "original_usage.prompt_tokens_details.text_tokens", 480,
                                    "original_usage.completion_tokens_details.audio_tokens", 100,
                                    "original_usage.completion_tokens_details.text_tokens", 400),
                            "36.80"));
        }

        @Test
        void textGenerationCostBillsOneHourCacheWritesAtTheOneHourRate() {
            var modelPrice = CACHE_PRICES.toBuilder()
                    .cacheCreationInputTokenPriceAbove1hr(new BigDecimal("0.02"))
                    .build();
            // Claude behind an OpenAI-compatible API: the prompt includes the 150 writes, 100 of them for an hour.
            var usage = spanUsage("openrouter", "prompt_tokens", 1150, "completion_tokens", 100,
                    "prompt_tokens_details.cache_creation_tokens", 150,
                    "prompt_tokens_details.cache_creation_token_details.ephemeral_1h_input_tokens", 100);

            // 1000 * 0.01 + 50 * 0.015 + 100 * 0.02 + 100 * 0.02
            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo("14.75");
        }

        @Test
        void textGenerationCostAppliesTheTierOfTheWholePrompt() {
            var tiered = CACHE_PRICES.toBuilder()
                    .promptTiers(List.of(ModelPrice.PromptTier.builder()
                            .threshold(ModelPrice.TIER_THRESHOLD_200K)
                            .inputPrice(new BigDecimal("0.1"))
                            .outputPrice(new BigDecimal("0.2"))
                            .cacheCreationInputTokenPrice(BigDecimal.ZERO)
                            .cacheReadInputTokenPrice(new BigDecimal("0.05"))
                            .build()))
                    .build();
            // 210,000 prompt tokens, 60,000 of them cached: 150,000 * 0.1 + 60,000 * 0.05 + 10 * 0.2
            var usage = spanUsage("openai", "prompt_tokens", 210_000, "completion_tokens", 10,
                    "prompt_tokens_details.cached_tokens", 60_000);

            assertThat(textCost(tiered, usage)).isEqualByComparingTo("18002.00");
        }
    }

    @Nested
    class Anthropic {

        // 1000 * 0.01 + 100 * 0.02 + 50 * 0.015 + 200 * 0.005 = 10 + 2 + 0.75 + 1
        private static final String CACHED_CALL_COST = "13.75";

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void textGenerationCostBillsEachCacheBucketAtItsRate(String description, SpanUsage usage) {
            assertThat(textCost(CACHE_PRICES, usage)).isEqualByComparingTo(CACHED_CALL_COST);
        }

        static Stream<Arguments> textGenerationCostBillsEachCacheBucketAtItsRate() {
            return Stream.of(
                    Arguments.of("Anthropic native",
                            spanUsage("anthropic", "input_tokens", 1000, "output_tokens", 100,
                                    "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50)),
                    Arguments.of("Anthropic native logged by the SDK",
                            spanUsage("anthropic", "prompt_tokens", 1250, "completion_tokens", 100,
                                    "total_tokens", 1350, "original_usage.input_tokens", 1000,
                                    "original_usage.output_tokens", 100,
                                    "original_usage.cache_read_input_tokens", 200,
                                    "original_usage.cache_creation_input_tokens", 50)),
                    Arguments.of("LiteLLM, whose prompt includes the cache and which details it",
                            spanUsage("anthropic", "prompt_tokens", 1250, "completion_tokens", 100,
                                    "total_tokens", 1350, "prompt_tokens_details.cached_tokens", 200,
                                    "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50)),
                    Arguments.of("Anthropic's input renamed prompt_tokens (OTel ingestion, the judge)",
                            spanUsage("anthropic", "prompt_tokens", 1000, "completion_tokens", 100,
                                    "total_tokens", 1100, "cache_read_input_tokens", 200,
                                    "cache_creation_input_tokens", 50)),
                    Arguments.of("Claude on Vertex",
                            spanUsage("vertex_ai-anthropic_models", "input_tokens", 1000, "output_tokens", 100,
                                    "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50)));
        }

        @Test
        void textGenerationCostBillsOneHourCacheWritesAtTheOneHourRate() {
            var modelPrice = CACHE_PRICES.toBuilder()
                    .cacheCreationInputTokenPriceAbove1hr(new BigDecimal("0.02"))
                    .build();
            var usage = spanUsage("anthropic", "input_tokens", 1000, "output_tokens", 100,
                    "cache_creation.ephemeral_5m_input_tokens", 50, "cache_creation.ephemeral_1h_input_tokens", 100);

            // 10 + 2 + 50 * 0.015 + 100 * 0.02
            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo("14.75");
        }

        @Test
        void textGenerationCostBillsOneHourCacheWritesAtTheWriteRateWithoutAOneHourRate() {
            var usage = spanUsage("anthropic", "input_tokens", 1000, "output_tokens", 100,
                    "cache_creation.ephemeral_5m_input_tokens", 50, "cache_creation.ephemeral_1h_input_tokens", 100);

            // 10 + 2 + 150 * 0.015
            assertThat(textCost(CACHE_PRICES, usage)).isEqualByComparingTo("14.25");
        }

        /**
         * The rates Claude Sonnet 4.5 published when a manual test on staging found a one-hour write above its 200K
         * tier billed at the base one-hour rate, below the tier's five-minute rate.
         */
        @Test
        void textGenerationCostBillsOneHourCacheWritesAboveATierAtItsOneHourRate() {
            var sonnet = ModelPrice.defaultBuilder()
                    .inputPrice(new BigDecimal("0.000003"))
                    .outputPrice(new BigDecimal("0.000015"))
                    .cacheCreationInputTokenPrice(new BigDecimal("0.00000375"))
                    .cacheReadInputTokenPrice(new BigDecimal("0.0000003"))
                    .cacheCreationInputTokenPriceAbove1hr(new BigDecimal("0.000006"))
                    .promptTiers(List.of(ModelPrice.PromptTier.builder()
                            .threshold(ModelPrice.TIER_THRESHOLD_200K)
                            .inputPrice(new BigDecimal("0.000006"))
                            .outputPrice(new BigDecimal("0.0000225"))
                            .cacheCreationInputTokenPrice(new BigDecimal("0.0000075"))
                            .cacheReadInputTokenPrice(new BigDecimal("0.0000006"))
                            .cacheCreationInputTokenPriceAbove1hr(new BigDecimal("0.000012"))
                            .build()))
                    .build();
            var fiveMinuteWrites = spanUsage("anthropic", "input_tokens", 200_000, "output_tokens", 1_000,
                    "cache_creation_input_tokens", 100_000, "cache_creation.ephemeral_5m_input_tokens", 100_000);
            var oneHourWrites = spanUsage("anthropic", "input_tokens", 200_000, "output_tokens", 1_000,
                    "cache_creation_input_tokens", 100_000, "cache_creation.ephemeral_1h_input_tokens", 100_000);

            // 200,000 * 6e-6 + 100,000 * 7.5e-6 + 1,000 * 2.25e-5
            assertThat(textCost(sonnet, fiveMinuteWrites)).isEqualByComparingTo("1.9725");
            // 200,000 * 6e-6 + 100,000 * 1.2e-5 + 1,000 * 2.25e-5
            assertThat(textCost(sonnet, oneHourWrites)).isEqualByComparingTo("2.4225");
        }

        @Test
        void textGenerationCostScalesTheOneHourRateAboveATierWithoutOne() {
            // The tier raises the five-minute write rate tenfold but publishes no one-hour rate.
            var tiered = CACHE_PRICES.toBuilder()
                    .cacheCreationInputTokenPriceAbove1hr(new BigDecimal("0.02"))
                    .promptTiers(List.of(ModelPrice.PromptTier.builder()
                            .threshold(ModelPrice.TIER_THRESHOLD_200K)
                            .inputPrice(new BigDecimal("0.1"))
                            .outputPrice(new BigDecimal("0.2"))
                            .cacheCreationInputTokenPrice(new BigDecimal("0.15"))
                            .cacheReadInputTokenPrice(BigDecimal.ZERO)
                            .build()))
                    .build();
            var usage = spanUsage("anthropic", "input_tokens", 200_000, "output_tokens", 10,
                    "cache_creation_input_tokens", 1_000, "cache_creation.ephemeral_1h_input_tokens", 1_000);

            // The one-hour rate keeps its base ratio to the five-minute one, 0.02 * 0.15 / 0.015:
            // 200,000 * 0.1 + 1,000 * 0.2 + 10 * 0.2
            assertThat(textCost(tiered, usage)).isEqualByComparingTo("20202.00");
        }

        @Test
        void textGenerationCostBillsCacheAsInputWithoutCacheRates() {
            var usage = spanUsage("anthropic", "input_tokens", 1000, "output_tokens", 100,
                    "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50);

            // (1000 + 200 + 50) * 0.01 + 100 * 0.02
            assertThat(textCost(NO_CACHE_PRICES, usage)).isEqualByComparingTo("14.50");
        }

        @Test
        void textGenerationCostAppliesTheTierOfThePromptWithItsCache() {
            var tiered = CACHE_PRICES.toBuilder()
                    .promptTiers(List.of(ModelPrice.PromptTier.builder()
                            .threshold(ModelPrice.TIER_THRESHOLD_200K)
                            .inputPrice(new BigDecimal("0.1"))
                            .outputPrice(new BigDecimal("0.2"))
                            .cacheCreationInputTokenPrice(BigDecimal.ZERO)
                            .cacheReadInputTokenPrice(new BigDecimal("0.05"))
                            .build()))
                    .build();
            // 150,000 uncached + 60,000 cached is over 200K: 150,000 * 0.1 + 60,000 * 0.05 + 10 * 0.2
            var usage = spanUsage("anthropic", "input_tokens", 150_000, "output_tokens", 10,
                    "cache_read_input_tokens", 60_000);

            assertThat(textCost(tiered, usage)).isEqualByComparingTo("18002.00");
        }
    }

    @Nested
    class BedrockConverse {

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void textGenerationCostBillsEachCacheBucketAtItsRate(String description, SpanUsage usage) {
            assertThat(textCost(CACHE_PRICES, usage)).isEqualByComparingTo("13.75");
        }

        static Stream<Arguments> textGenerationCostBillsEachCacheBucketAtItsRate() {
            return Stream.of(
                    Arguments.of("Converse",
                            spanUsage("bedrock", "inputTokens", 1000, "outputTokens", 100,
                                    "cacheReadInputTokens", 200, "cacheWriteInputTokens", 50)),
                    Arguments.of("LiteLLM, whose prompt excludes the cache on Bedrock",
                            spanUsage("bedrock", "prompt_tokens", 1000, "completion_tokens", 100,
                                    "cache_read_input_tokens", 200, "cache_creation_input_tokens", 50)),
                    Arguments.of("Converse provider",
                            spanUsage("bedrock_converse", "inputTokens", 1000, "outputTokens", 100,
                                    "cacheReadInputTokens", 200, "cacheWriteInputTokens", 50)));
        }

        @Test
        void textGenerationCostBillsOneHourCacheWritesAtTheOneHourRate() {
            var modelPrice = CACHE_PRICES.toBuilder()
                    .cacheCreationInputTokenPriceAbove1hr(new BigDecimal("0.02"))
                    .build();
            var usage = spanUsage("bedrock", "inputTokens", 1000, "outputTokens", 100,
                    "cacheWriteInputTokens", 150, "cacheDetails.5m.inputTokens", 50, "cacheDetails.1h.inputTokens",
                    100);

            // 1000 * 0.01 + 100 * 0.02 + 50 * 0.015 + 100 * 0.02
            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo("14.75");
        }
    }

    @Nested
    class Gemini {

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void textGenerationCost(String description, ModelPrice modelPrice, SpanUsage usage, String expectedCost) {
            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo(expectedCost);
        }

        static Stream<Arguments> textGenerationCost() {
            ModelPrice withReasoningRate = CACHE_PRICES.toBuilder()
                    .outputReasoningTokenPrice(new BigDecimal("0.003"))
                    .build();
            return Stream.of(
                    // (1000 - 300) * 0.01 + 300 * 0.005 + 100 * 0.02 = 7 + 1.5 + 2
                    Arguments.of("context cache is part of the prompt", CACHE_PRICES,
                            spanUsage("gemini", "original_usage.prompt_token_count", 1000,
                                    "original_usage.candidates_token_count", 100,
                                    "original_usage.cached_content_token_count", 300),
                            "10.50"),
                    // (1000 - 200) * 0.01 + 200 * 0.005 + 100 * 0.02 = 8 + 1 + 2
                    Arguments.of("flat OTel cache key", CACHE_PRICES,
                            spanUsage("gemini", "prompt_tokens", 1000, "completion_tokens", 100,
                                    "cache_read_input_tokens", 200),
                            "11.00"),
                    // (27 + 10309) * 0.01 + (45 + 31) * 0.02 = 103.36 + 1.52
                    Arguments.of("tool-use prompt tokens are input", CACHE_PRICES,
                            spanUsage("gemini", "original_usage.prompt_token_count", 27,
                                    "original_usage.candidates_token_count", 45,
                                    "original_usage.thoughts_token_count", 31,
                                    "original_usage.tool_use_prompt_token_count", 10309),
                            "104.88"),
                    // (1000 + 500 - 300) * 0.01 + 300 * 0.005 + 100 * 0.02 = 12 + 1.5 + 2
                    Arguments.of("tool use with context cache", CACHE_PRICES,
                            spanUsage("gemini", "original_usage.prompt_token_count", 1000,
                                    "original_usage.candidates_token_count", 100,
                                    "original_usage.tool_use_prompt_token_count", 500,
                                    "original_usage.cached_content_token_count", 300),
                            "15.50"),
                    // 1000 * 0.01 + (100 + 50) * 0.02: the total LangChain reports leaves the thoughts out
                    Arguments.of("thoughts bill as output even when the total leaves them out", CACHE_PRICES,
                            spanUsage("gemini", "original_usage.prompt_token_count", 1000,
                                    "original_usage.candidates_token_count", 100,
                                    "original_usage.thoughts_token_count", 50,
                                    "original_usage.total_token_count", 1100),
                            "13.00"),
                    // 10 + 100 * 0.02 + 50 * 0.003
                    Arguments.of("thoughts bill at the reasoning rate when there is one", withReasoningRate,
                            spanUsage("gemini", "original_usage.prompt_token_count", 1000,
                                    "original_usage.candidates_token_count", 100,
                                    "original_usage.thoughts_token_count", 50),
                            "12.15"));
        }
    }

    @Nested
    class WithoutNativeUsage {

        @Test
        void textGenerationCostBillsTheClientTotals() {
            var usage = spanUsage(null, "prompt_tokens", 1000, "completion_tokens", 100);

            assertThat(textCost(CACHE_PRICES, usage)).isEqualByComparingTo("12.00");
        }

        @Test
        void textGenerationCostOfEmptyUsageIsZero() {
            assertThat(textCost(CACHE_PRICES, spanUsage("openai"))).isZero();
        }

        @Test
        void defaultCostIsZero() {
            assertThat(SpanCostCalculator.defaultCost(CACHE_PRICES,
                    spanUsage("openai", "prompt_tokens", 1000, "completion_tokens", 100))).isZero();
        }
    }

    /** The SDK's totals next to only some of the provider's keys, which the provider's format can't read alone. */
    @Nested
    class SdkTotalsWithSomeProviderKeys {

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void textGenerationCostBillsTheDetailsAtTheirRates(String description, ModelPrice modelPrice,
                SpanUsage usage, String expectedCost) {
            assertThat(textCost(modelPrice, usage)).isEqualByComparingTo(expectedCost);
        }

        static Stream<Arguments> textGenerationCostBillsTheDetailsAtTheirRates() {
            ModelPrice geminiLivePrices = ModelPrice.defaultBuilder()
                    .inputPrice(new BigDecimal("0.00000075"))
                    .outputPrice(new BigDecimal("0.0000045"))
                    .inputAudioTokenPrice(new BigDecimal("0.000003"))
                    .outputAudioTokenPrice(new BigDecimal("0.000012"))
                    .build();
            return Stream.of(
                    // Production: (9,172 - 3,747) * 0.00000075 + 3,747 * 0.000003 + 256 * 0.000012
                    Arguments.of("Gemini Live audio", geminiLivePrices,
                            spanUsage("google_ai", "prompt_tokens", 9172, "completion_tokens", 256,
                                    "total_tokens", 9428, "original_usage.prompt_tokens_details.audio_tokens", 3747,
                                    "original_usage.completion_tokens_details.audio_tokens", 256,
                                    "original_usage.thoughtsTokenCount", 539),
                            "0.01838175"),
                    // 1000 * 0.01 + (100 - 40) * 0.02 + 40 * 0.003
                    Arguments.of("reasoning", NO_CACHE_PRICES.toBuilder()
                            .outputReasoningTokenPrice(new BigDecimal("0.003")).build(),
                            spanUsage("gemini", "prompt_tokens", 1000, "completion_tokens", 100,
                                    "original_usage.completion_tokens_details.reasoning_tokens", 40),
                            "11.32"));
        }
    }

    /**
     * Every text-generation case of {@code main}'s calculator tests, with its usage, rates and expected cost unchanged.
     * {@code main} picked a calculator by provider: each case runs under the provider whose calculator it covered, and
     * the cases of its default calculator under OpenAI's format, which every provider without one of its own reads.
     */
    @Nested
    class CasesFromMain {

        private static final ModelPrice NO_CACHE = ModelPrice.defaultBuilder()
                .inputPrice(new BigDecimal("0.01"))
                .outputPrice(new BigDecimal("0.02"))
                .build();
        private static final ModelPrice CACHE_READ = NO_CACHE.toBuilder()
                .cacheReadInputTokenPrice(new BigDecimal("0.005"))
                .build();
        private static final ModelPrice REASONING = NO_CACHE.toBuilder()
                .outputReasoningTokenPrice(new BigDecimal("0.003"))
                .build();
        private static final ModelPrice REASONING_AND_AUDIO = REASONING.toBuilder()
                .outputAudioTokenPrice(new BigDecimal("0.08"))
                .build();
        private static final ModelPrice CACHE_READ_AND_AUDIO = CACHE_READ.toBuilder()
                .inputAudioTokenPrice(new BigDecimal("0.05"))
                .outputAudioTokenPrice(new BigDecimal("0.08"))
                .build();

        @ParameterizedTest(name = "{0} ({1})")
        @MethodSource
        void textGenerationCost(String description, String provider, ModelPrice modelPrice,
                Map<String, Integer> usage, String expectedCost) {
            assertThat(textCost(modelPrice, SpanUsageFactory.create(provider, usage)))
                    .isEqualByComparingTo(expectedCost);
        }

        static Stream<Arguments> textGenerationCost() {
            return Stream.of(
                    // Default calculator: reasoning
                    Arguments.of("reasoning rate set: bare reasoning key", "openai", REASONING,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.reasoning_tokens", 200),
                            "16.60"),
                    Arguments.of("reasoning rate set: original_usage.* reasoning key", "openai", REASONING,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 500,
                                    "original_usage.completion_tokens_details.reasoning_tokens", 200),
                            "16.60"),
                    Arguments.of("reasoning and audio rates set", "openai", REASONING_AND_AUDIO,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.audio_tokens", 100,
                                    "completion_tokens_details.reasoning_tokens", 200),
                            "22.60"),
                    Arguments.of("reasoning rate unset", "openai", NO_CACHE,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.reasoning_tokens", 200),
                            "20.00"),
                    Arguments.of("reasoning rate set, no reasoning key", "openai", REASONING,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 500),
                            "20.00"),
                    Arguments.of("reasoning equals completion", "openai", REASONING,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.reasoning_tokens", 500),
                            "11.50"),
                    Arguments.of("over-reported reasoning", "openai", REASONING,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                                    "completion_tokens_details.reasoning_tokens", 200),
                            "10.30"),
                    Arguments.of("negative reasoning", "openai", REASONING,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 500,
                                    "completion_tokens_details.reasoning_tokens", -10),
                            "20.00"),
                    // OpenAI cache calculator
                    Arguments.of("OTel cache_read_input_tokens key", "openai", CACHE_READ,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 100, "cache_read_input_tokens", 200),
                            "11.00"),
                    Arguments.of("original_usage key takes precedence over OTel key", "openai", CACHE_READ,
                            Map.of("original_usage.prompt_tokens", 1000, "original_usage.completion_tokens", 100,
                                    "original_usage.prompt_tokens_details.cached_tokens", 300,
                                    "cache_read_input_tokens", 200),
                            "10.50"),
                    Arguments.of("Responses API input_tokens_details.cached_tokens key", "openai", CACHE_READ,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 100,
                                    "original_usage.input_tokens_details.cached_tokens", 300),
                            "10.50"),
                    Arguments.of("audio rates set: realtime usage", "openai", CACHE_READ_AND_AUDIO,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 500, "cache_read_input_tokens", 200,
                                    "prompt_tokens_details.audio_tokens", 300,
                                    "completion_tokens_details.audio_tokens", 100),
                            "37.00"),
                    Arguments.of("audio rates unset: audio keys ignored", "openai", CACHE_READ,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 100, "cache_read_input_tokens", 200,
                                    "prompt_tokens_details.audio_tokens", 300,
                                    "completion_tokens_details.audio_tokens", 50),
                            "11.00"),
                    Arguments.of("audio rates set: original_usage.* keys", "openai", CACHE_READ_AND_AUDIO,
                            Map.of("original_usage.prompt_tokens", 1000, "original_usage.completion_tokens", 500,
                                    "original_usage.prompt_tokens_details.cached_tokens", 200,
                                    "original_usage.prompt_tokens_details.audio_tokens", 300,
                                    "original_usage.completion_tokens_details.audio_tokens", 100),
                            "37.00"),
                    Arguments.of("text_tokens preferred over subtraction", "openai", CACHE_READ_AND_AUDIO,
                            Map.of("original_usage.prompt_tokens", 1000, "original_usage.completion_tokens", 500,
                                    "original_usage.prompt_tokens_details.cached_tokens", 200,
                                    "original_usage.prompt_tokens_details.audio_tokens", 300,
                                    "original_usage.prompt_tokens_details.text_tokens", 480,
                                    "original_usage.completion_tokens_details.audio_tokens", 100,
                                    "original_usage.completion_tokens_details.text_tokens", 400),
                            "36.80"),
                    // Google cache calculator
                    Arguments.of("Gemini cached_content_token_count key", "gemini", CACHE_READ,
                            Map.of("original_usage.prompt_token_count", 1000, "completion_tokens", 100,
                                    "original_usage.cached_content_token_count", 300),
                            "10.50"),
                    Arguments.of("Gemini OTel cache_read_input_tokens key", "gemini", CACHE_READ,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 100, "cache_read_input_tokens", 200),
                            "11.00"),
                    Arguments.of("Gemini tool_use_prompt_token_count counted as input", "gemini", CACHE_READ,
                            Map.of("original_usage.prompt_token_count", 27, "completion_tokens", 76,
                                    "original_usage.tool_use_prompt_token_count", 10309),
                            "104.88"),
                    Arguments.of("Gemini tool use with cached content", "gemini", CACHE_READ,
                            Map.of("original_usage.prompt_token_count", 1000, "completion_tokens", 100,
                                    "original_usage.tool_use_prompt_token_count", 500,
                                    "original_usage.cached_content_token_count", 300),
                            "15.50"),
                    Arguments.of("Gemini prompt_tokens already includes tool use", "gemini", CACHE_READ,
                            Map.of("prompt_tokens", 10336, "completion_tokens", 76,
                                    "original_usage.tool_use_prompt_token_count", 10309),
                            "104.88"),
                    // Anthropic and Bedrock cache calculators: OTel cache keys next to Anthropic's input as prompt_tokens
                    Arguments.of("Anthropic OTel cache keys", "anthropic", CACHE_PRICES,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 100, "cache_read_input_tokens", 200,
                                    "cache_creation_input_tokens", 50),
                            "13.75"),
                    Arguments.of("Bedrock OTel cache keys", "bedrock", CACHE_PRICES,
                            Map.of("prompt_tokens", 1000, "completion_tokens", 100, "cache_read_input_tokens", 200,
                                    "cache_creation_input_tokens", 50),
                            "13.75"));
        }
    }

    @Nested
    class VideoGeneration {

        private static final ModelPrice VIDEO_PRICES = ModelPrice.defaultBuilder()
                .videoOutputPrice(new BigDecimal("0.5"))
                .build();

        @Test
        void videoGenerationCostReturnsZeroWhenPriceIsZero() {
            assertThat(SpanCostCalculator.videoGenerationCost(ModelPrice.empty(),
                    spanUsage("openai", "video_duration_seconds", 10))).isZero();
        }

        @Test
        void videoGenerationCostMultipliesDurationAndPrice() {
            assertThat(SpanCostCalculator.videoGenerationCost(VIDEO_PRICES,
                    spanUsage("openai", "video_duration_seconds", 2))).isEqualByComparingTo("1.0");
        }

        @Test
        void videoGenerationCostReadsTheDurationTheSdkLogged() {
            assertThat(SpanCostCalculator.videoGenerationCost(VIDEO_PRICES,
                    spanUsage("openai", "original_usage.video_duration_seconds", 2))).isEqualByComparingTo("1.0");
        }

        @Test
        void videoGenerationCostValidatesArguments() {
            assertThatThrownBy(() -> SpanCostCalculator.videoGenerationCost(null, spanUsage("openai")))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SpanCostCalculator.videoGenerationCost(VIDEO_PRICES, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class AudioSpeech {

        @ParameterizedTest
        @MethodSource
        void audioSpeechCostReturnsZero(String pricePerCharacter, SpanUsage usage) {
            var modelPrice = ModelPrice.defaultBuilder()
                    .audioInputCharacterPrice(new BigDecimal(pricePerCharacter))
                    .build();

            assertThat(SpanCostCalculator.audioSpeechCost(modelPrice, usage)).isZero();
        }

        static Stream<Arguments> audioSpeechCostReturnsZero() {
            return Stream.of(
                    Arguments.of("0", spanUsage("openai", "input_characters", 100)),
                    Arguments.of("0.000015", spanUsage("openai")));
        }

        @ParameterizedTest
        @MethodSource
        void audioSpeechCostMultipliesCharactersAndPrice(String pricePerCharacter, SpanUsage usage,
                String expectedCost) {
            var modelPrice = ModelPrice.defaultBuilder()
                    .audioInputCharacterPrice(new BigDecimal(pricePerCharacter))
                    .build();

            assertThat(SpanCostCalculator.audioSpeechCost(modelPrice, usage)).isEqualByComparingTo(expectedCost);
        }

        static Stream<Arguments> audioSpeechCostMultipliesCharactersAndPrice() {
            return Stream.of(
                    // tts-1: 1000 characters * 0.000015
                    Arguments.of("0.000015", spanUsage("openai", "input_characters", 1000), "0.015"),
                    // tts-1-hd: 500 characters * 0.000030
                    Arguments.of("0.000030", spanUsage("openai", "input_characters", 500), "0.015"),
                    Arguments.of("0.000015", spanUsage("openai", "original_usage.input_characters", 1000), "0.015"));
        }

        @Test
        void audioSpeechCostValidatesArguments() {
            var modelPrice = ModelPrice.defaultBuilder()
                    .audioInputCharacterPrice(new BigDecimal("0.000015"))
                    .build();

            assertThatThrownBy(() -> SpanCostCalculator.audioSpeechCost(null, spanUsage("openai")))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SpanCostCalculator.audioSpeechCost(modelPrice, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
