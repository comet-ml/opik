package com.comet.opik.domain.token_usage;

import com.comet.opik.domain.token_usage.UsageCases.UsageCase;
import com.comet.opik.domain.token_usage.model.AnthropicNativeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiLikeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiNativeUsage;
import com.comet.opik.domain.token_usage.model.OriginalUsage;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import com.comet.opik.domain.token_usage.model.UsageWarning;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.comet.opik.domain.token_usage.UsageCases.usage;
import static org.assertj.core.api.Assertions.assertThat;

class SpanUsageFactoryTest {

    private static final String ALL_CASES = "com.comet.opik.domain.token_usage.UsageCases#all";

    @Nested
    class NativeUsageNormalization {

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void create(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage());

            assertThat(spanUsage.nativeUsage()).isEqualTo(usageCase.expected());
            assertThat(spanUsage.openAiLike()).isEqualTo(usageCase.expected().toOpenAiLike());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void createKeepsTheOriginalUsage(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage());

            assertThat(spanUsage.original()).isEqualTo(OriginalUsage.from(usageCase.usage()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void createReportsNoWarningForConsistentUsage(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage());

            assertThat(spanUsage.warnings()).isEmpty();
        }
    }

    @Nested
    class WithoutNativeUsage {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        void createWhenProviderIsBlankKeepsTheClientTotals(String provider) {
            var usage = usage("input_tokens", 200, "output_tokens", 100, "cache_read_input_tokens", 30,
                    "prompt_tokens", 230, "completion_tokens", 100, "total_tokens", 330);

            var spanUsage = SpanUsageFactory.create(provider, usage);

            assertThat(spanUsage).isEqualTo(new SpanUsage(OriginalUsage.from(usage), null,
                    new OpenAiLikeUsage(230L, 100L, 330L, null), List.of()));
        }

        @ParameterizedTest
        @NullAndEmptySource
        void createWhenUsageIsEmptyReturnsEmptyUsage(Map<String, Integer> usage) {
            var spanUsage = SpanUsageFactory.create("anthropic", usage);

            assertThat(spanUsage).isEqualTo(new SpanUsage(OriginalUsage.EMPTY, null, OpenAiLikeUsage.EMPTY, List.of()));
        }

        @Test
        void createWhenEveryCountIsNullReturnsEmptyUsage() {
            var usage = new HashMap<String, Integer>();
            usage.put("prompt_token_count", null);
            usage.put("candidates_token_count", null);

            var spanUsage = SpanUsageFactory.create("gemini", usage);

            assertThat(spanUsage).isEqualTo(new SpanUsage(OriginalUsage.EMPTY, null, OpenAiLikeUsage.EMPTY, List.of()));
        }

        @Test
        void createWhenNoInputVariantMatchesReportsTheKeysAndKeepsUsageAsSent() {
            var usage = usage("characters", 1200, "images", 2);

            var spanUsage = SpanUsageFactory.create("gemini", usage);

            assertThat(spanUsage).isEqualTo(new SpanUsage(OriginalUsage.from(usage), null, OpenAiLikeUsage.EMPTY,
                    List.of(new UsageWarning(UsageWarning.Type.NO_MATCHING_VARIANT,
                            "provider 'gemini', keys [characters, images]"))));
        }

        @Test
        void createWhenNoInputVariantMatchesKeepsTheTotalTheClientSent() {
            var usage = usage("total_tokens", 30, "characters", 1200);

            var spanUsage = SpanUsageFactory.create("openai", usage);

            assertThat(spanUsage.nativeUsage()).isNull();
            assertThat(spanUsage.openAiLike()).isEqualTo(new OpenAiLikeUsage(null, null, 30L, null));
        }

        @Test
        void createWhenTheClientSentPromptAndCompletionAddsTheirSumAsTotal() {
            var usage = usage("prompt_tokens", 20, "completion_tokens", 10, "some_provider_field", 5);

            var spanUsage = SpanUsageFactory.create(null, usage);

            assertThat(spanUsage.openAiLike()).isEqualTo(new OpenAiLikeUsage(20L, 10L, 30L, null));
        }

        @Test
        void createWhenTheClientSentOnlyThePromptLeavesTheOtherTotalsUnknown() {
            var usage = usage("prompt_tokens", 200);

            var spanUsage = SpanUsageFactory.create(null, usage);

            assertThat(spanUsage.openAiLike()).isEqualTo(new OpenAiLikeUsage(200L, null, null, null));
        }
    }

    /** SDK-converted usage whose unpacked provider keys no variant reads alone. */
    @Nested
    class SdkTotalsWithUnreadableProviderKeys {

        @Test
        void createWhenNoVariantReadsTheSdkConvertedUsageReadsItWithTheSdkTotalsAsOpenAiUsage() {
            var usage = usage("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30,
                    "original_usage.characters", 1200);

            var spanUsage = SpanUsageFactory.create("openai", usage);

            assertThat(spanUsage.nativeUsage()).isEqualTo(OpenAiNativeUsage.builder().provider("openai")
                    .variant("chat_completions").promptTokens(20).completionTokens(10).totalTokens(30).build());
            assertThat(spanUsage.original()).isEqualTo(OriginalUsage.from(usage));
        }

        // Gemini usage logged under Bedrock (production): Llama's prompt_token_count must not make it Llama usage,
        // which has no candidates_token_count and would lose the completion.
        @Test
        void createWhenBedrockUsageIsGeminiShapedReadsTheSdkTotals() {
            var usage = usage("prompt_tokens", 55659, "completion_tokens", 438, "total_tokens", 56097,
                    "original_usage.prompt_token_count", 55659, "original_usage.candidates_token_count", 438,
                    "original_usage.cached_content_token_count", 54719, "original_usage.total_token_count", 56097);

            var spanUsage = SpanUsageFactory.create("bedrock", usage);

            assertThat(spanUsage.nativeUsage()).isInstanceOf(OpenAiNativeUsage.class);
            assertThat(spanUsage.openAiLike()).isEqualTo(OpenAiLikeUsage.of(55659, 438, 56097, 0));
        }
    }

    @Nested
    class Warnings {

        @Test
        void createWhenAnInputIncludingTheCacheIsBelowItReportsItAndTakesItAsExcludingTheCache() {
            var spanUsage = SpanUsageFactory.create("anthropic", usage("prompt_tokens", 100, "completion_tokens", 5,
                    "cache_read_input_tokens", 500, "prompt_tokens_details.cached_tokens", 500));

            assertThat(spanUsage.nativeUsage()).isEqualTo(AnthropicNativeUsage.builder().provider("anthropic")
                    .variant("litellm").inputTokens(100).cacheReadInputTokens(500).outputTokens(5).build());
            assertThat(spanUsage.warnings()).containsExactly(new UsageWarning(UsageWarning.Type.CACHE_EXCEEDS_INPUT,
                    "provider 'anthropic', variant 'litellm': input 100, cache 500"));
        }

        @Test
        void createWhenAnInputIncludingTheCacheIsBelowItReportsItAndAddsTheCacheToThePrompt() {
            var spanUsage = SpanUsageFactory.create("openai",
                    usage("prompt_tokens", 100, "completion_tokens", 5, "prompt_tokens_details.cached_tokens", 500));

            assertThat(spanUsage.nativeUsage()).isEqualTo(OpenAiNativeUsage.builder().provider("openai")
                    .variant("chat_completions").promptTokens(600).cachedTokens(500).completionTokens(5).build());
            assertThat(spanUsage.warnings())
                    .containsExactly(UsageWarning.cacheExceedsInput("openai", "chat_completions", 100, 500));
        }

        @Test
        void createReturnsAnImmutableWarningList() {
            var spanUsage = SpanUsageFactory.create("openai",
                    usage("prompt_tokens", 100, "prompt_tokens_details.cached_tokens", 500));

            assertThat(spanUsage.warnings()).isUnmodifiable();
        }
    }

    @Nested
    class Invariants {

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void openAiLikeCountsAreNonNegative(UsageCase usageCase) {
            var openAiLike = SpanUsageFactory.create(usageCase.provider(), usageCase.usage())
                    .openAiLike();

            assertThat(List.of(openAiLike.promptTokens(), openAiLike.completionTokens(), openAiLike.totalTokens(),
                    openAiLike.cachedPromptTokens())).allSatisfy(count -> assertThat(count).isNotNegative());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void openAiLikeCachedTokensAreWithinThePrompt(UsageCase usageCase) {
            var openAiLike = SpanUsageFactory.create(usageCase.provider(), usageCase.usage())
                    .openAiLike();

            assertThat(openAiLike.cachedPromptTokens()).isLessThanOrEqualTo(openAiLike.promptTokens());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void openAiLikeTotalIsPromptPlusCompletion(UsageCase usageCase) {
            var openAiLike = SpanUsageFactory.create(usageCase.provider(), usageCase.usage())
                    .openAiLike();

            assertThat(openAiLike.totalTokens()).isEqualTo(openAiLike.promptTokens() + openAiLike.completionTokens());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void nativeUsageCarriesTheProvider(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage());

            assertThat(spanUsage.nativeUsage().provider()).isEqualTo(usageCase.provider());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void createDoesNotModifyTheUsageItReceives(UsageCase usageCase) {
            var usage = Map.copyOf(usageCase.usage());

            SpanUsageFactory.create(usageCase.provider(), usageCase.usage());

            assertThat(usageCase.usage()).isEqualTo(usage);
        }
    }
}
