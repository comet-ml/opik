package com.comet.opik.domain.token_usage;

import com.comet.opik.domain.token_usage.UsageCases.UsageCase;
import com.comet.opik.domain.token_usage.model.AnthropicNativeUsage;
import com.comet.opik.domain.token_usage.model.GeminiNativeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiLikeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiNativeUsage;
import com.comet.opik.domain.token_usage.model.OriginalUsage;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import com.comet.opik.domain.token_usage.model.UsageContext;
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
import java.util.stream.Stream;

import static com.comet.opik.domain.token_usage.UsageCases.usage;
import static com.comet.opik.domain.token_usage.model.OriginalUsage.ORIGINAL_USAGE_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

class SpanUsageFactoryTest {

    private static final String ALL_CASES = "com.comet.opik.domain.token_usage.UsageCases#all";
    private static final UsageContext OTEL_RENAMED = UsageContext.builder().otelRenamedKeys(true).build();

    @Nested
    class NativeUsageNormalization {

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void create(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE);

            assertThat(spanUsage.nativeUsage()).isEqualTo(usageCase.expected());
            assertThat(spanUsage.openAiLike()).isEqualTo(usageCase.expected().toOpenAiLike());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void createKeepsTheOriginalUsage(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE);

            assertThat(spanUsage.original()).isEqualTo(OriginalUsage.from(usageCase.usage()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void createReportsNoWarningForConsistentUsage(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE);

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

            var spanUsage = SpanUsageFactory.create(provider, usage, UsageContext.NONE);

            assertThat(spanUsage).isEqualTo(new SpanUsage(OriginalUsage.from(usage), null,
                    new OpenAiLikeUsage(230L, 100L, 330L, null), List.of()));
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage);
        }

        @ParameterizedTest
        @NullAndEmptySource
        void createWhenUsageIsEmptyReturnsEmptyUsage(Map<String, Integer> usage) {
            var spanUsage = SpanUsageFactory.create("anthropic", usage, UsageContext.NONE);

            assertThat(spanUsage).isEqualTo(new SpanUsage(OriginalUsage.EMPTY, null, OpenAiLikeUsage.EMPTY, List.of()));
            assertThat(spanUsage.toUsageMap()).isEmpty();
        }

        @Test
        void createWhenEveryCountIsNullReturnsEmptyUsage() {
            var usage = new HashMap<String, Integer>();
            usage.put("prompt_token_count", null);
            usage.put("candidates_token_count", null);

            var spanUsage = SpanUsageFactory.create("gemini", usage, UsageContext.NONE);

            assertThat(spanUsage).isEqualTo(new SpanUsage(OriginalUsage.EMPTY, null, OpenAiLikeUsage.EMPTY, List.of()));
        }

        @Test
        void createWhenNoInputVariantMatchesReportsTheKeysAndKeepsUsageAsSent() {
            var usage = usage("characters", 1200, "images", 2);

            var spanUsage = SpanUsageFactory.create("gemini", usage, UsageContext.NONE);

            assertThat(spanUsage).isEqualTo(new SpanUsage(OriginalUsage.from(usage), null, OpenAiLikeUsage.EMPTY,
                    List.of(new UsageWarning(UsageWarning.Type.NO_MATCHING_VARIANT,
                            "provider 'gemini', keys [characters, images]"))));
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage);
        }

        @Test
        void createWhenNoInputVariantMatchesKeepsTheTotalTheClientSent() {
            var usage = usage("total_tokens", 30, "characters", 1200);

            var spanUsage = SpanUsageFactory.create("openai", usage, UsageContext.NONE);

            assertThat(spanUsage.nativeUsage()).isNull();
            assertThat(spanUsage.openAiLike()).isEqualTo(new OpenAiLikeUsage(null, null, 30L, null));
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage);
        }

        @Test
        void createWhenTheClientSentPromptAndCompletionAddsTheirSumAsTotal() {
            var usage = usage("prompt_tokens", 20, "completion_tokens", 10, "some_provider_field", 5);

            var spanUsage = SpanUsageFactory.create(null, usage, UsageContext.NONE);

            assertThat(spanUsage.openAiLike()).isEqualTo(new OpenAiLikeUsage(20L, 10L, 30L, null));
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage("prompt_tokens", 20, "completion_tokens", 10,
                    "total_tokens", 30, "some_provider_field", 5));
        }

        @Test
        void createWhenTheClientSentOnlyThePromptLeavesTheOtherTotalsUnknown() {
            var usage = usage("prompt_tokens", 200);

            var spanUsage = SpanUsageFactory.create(null, usage, UsageContext.NONE);

            assertThat(spanUsage.openAiLike()).isEqualTo(new OpenAiLikeUsage(200L, null, null, null));
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage);
        }

        @Test
        void createWhenTheSdkConvertedUsageWithoutVariantReadsTheSdkTotals() {
            var usage = usage("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30,
                    "original_usage.characters", 1200);

            var spanUsage = SpanUsageFactory.create("openai", usage, UsageContext.NONE);

            assertThat(spanUsage.nativeUsage()).isNull();
            assertThat(spanUsage.openAiLike()).isEqualTo(new OpenAiLikeUsage(20L, 10L, 30L, null));
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage);
        }
    }

    @Nested
    class Warnings {

        @Test
        void createWhenAnInputIncludingTheCacheIsBelowItReportsItAndTakesItAsExcludingTheCache() {
            var spanUsage = SpanUsageFactory.create("anthropic",
                    usage("prompt_tokens", 100, "completion_tokens", 5, "cache_read_input_tokens", 500),
                    UsageContext.NONE);

            assertThat(spanUsage.nativeUsage()).isEqualTo(AnthropicNativeUsage.builder().provider("anthropic")
                    .variant("litellm").inputTokens(100).cacheReadInputTokens(500).outputTokens(5).build());
            assertThat(spanUsage.warnings()).containsExactly(new UsageWarning(UsageWarning.Type.CACHE_EXCEEDS_INPUT,
                    "provider 'anthropic', variant 'litellm': input 100, cache 500"));
        }

        @Test
        void createWhenInputIncludingTheCacheIsBelowItReportsItAndKeepsTheProviderValues() {
            var spanUsage = SpanUsageFactory.create("openai",
                    usage("prompt_tokens", 100, "completion_tokens", 5, "prompt_tokens_details.cached_tokens", 500),
                    UsageContext.NONE);

            assertThat(spanUsage.nativeUsage()).isEqualTo(OpenAiNativeUsage.builder().provider("openai")
                    .variant("chat_completions").promptTokens(100).cachedTokens(500).completionTokens(5).build());
            assertThat(spanUsage.warnings())
                    .containsExactly(UsageWarning.cacheExceedsInput("openai", "chat_completions", 100, 500));
        }

        @Test
        void createReturnsAnImmutableWarningList() {
            var spanUsage = SpanUsageFactory.create("openai",
                    usage("prompt_tokens", 100, "prompt_tokens_details.cached_tokens", 500), UsageContext.NONE);

            assertThat(spanUsage.warnings()).isUnmodifiable();
        }
    }

    @Nested
    class OtelRenamedKeys {

        // What OTel ingestion stores for gen_ai.usage.input_tokens=2000, output_tokens=500, cache_read_tokens=10000.
        private static final Map<String, Integer> RENAMED_ANTHROPIC = usage("prompt_tokens", 2000,
                "completion_tokens", 500, "cache_read_input_tokens", 10000);

        @Test
        void createRestoresTheInstrumentationNamesSoAnthropicInputExcludesTheCache() {
            var spanUsage = SpanUsageFactory.create("anthropic", RENAMED_ANTHROPIC, OTEL_RENAMED);

            assertThat(spanUsage.nativeUsage()).isEqualTo(AnthropicNativeUsage.builder().provider("anthropic")
                    .variant("anthropic_native").inputTokens(2000).cacheReadInputTokens(10000).outputTokens(500)
                    .build());
            assertThat(spanUsage.original().keys()).isEqualTo(usage("input_tokens", 2000, "output_tokens", 500,
                    "cache_read_input_tokens", 10000));
            assertThat(spanUsage.warnings()).isEmpty();
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage("original_usage.input_tokens", 2000,
                    "original_usage.output_tokens", 500, "original_usage.cache_read_input_tokens", 10000,
                    "prompt_tokens", 12000, "completion_tokens", 500, "total_tokens", 12500));
        }

        @Test
        void createWithoutTheHintReadsTheRenamedKeysAsLiteLlmUsage() {
            var spanUsage = SpanUsageFactory.create("anthropic", RENAMED_ANTHROPIC, UsageContext.NONE);

            assertThat(spanUsage.nativeUsage().variant()).isEqualTo("litellm");
            assertThat(spanUsage.warnings())
                    .containsExactly(UsageWarning.cacheExceedsInput("anthropic", "litellm", 2000, 10000));
        }

        @Test
        void createFromTheStoredUsageNeedsNoHint() {
            var stored = SpanUsageFactory.create("anthropic", RENAMED_ANTHROPIC, OTEL_RENAMED).toUsageMap();

            var withHint = SpanUsageFactory.create("anthropic", stored, OTEL_RENAMED);
            var withoutHint = SpanUsageFactory.create("anthropic", stored, UsageContext.NONE);

            assertThat(withHint).isEqualTo(withoutHint);
            assertThat(withoutHint.toUsageMap()).isEqualTo(stored);
        }

        @Test
        void createGivesOpenAiTheSameTotalsWithOrWithoutTheHint() {
            var usage = usage("prompt_tokens", 12000, "completion_tokens", 500, "cache_read_input_tokens", 10000);

            var withHint = SpanUsageFactory.create("openai", usage, OTEL_RENAMED);
            var withoutHint = SpanUsageFactory.create("openai", usage, UsageContext.NONE);

            assertThat(withHint.nativeUsage().variant()).isEqualTo("responses");
            assertThat(withoutHint.nativeUsage().variant()).isEqualTo("chat_completions");
            assertThat(withHint.openAiLike()).isEqualTo(OpenAiLikeUsage.of(12000, 500, 12500, 10000));
            assertThat(withoutHint.openAiLike()).isEqualTo(withHint.openAiLike());
        }

        @Test
        void createReadsTheGeminiCacheUnderTheRestoredNames() {
            // What OTel ingestion stores for gen_ai.usage.input_tokens=4000, output_tokens=500, cache_read_tokens=3000.
            var usage = usage("prompt_tokens", 4000, "completion_tokens", 500, "cache_read_input_tokens", 3000);

            var spanUsage = SpanUsageFactory.create("gemini", usage, OTEL_RENAMED);

            assertThat(spanUsage.nativeUsage()).isEqualTo(GeminiNativeUsage.builder().provider("gemini")
                    .variant("responses_style").promptTokenCount(4000).cachedContentTokenCount(3000)
                    .candidatesTokenCount(500).build());
        }

        @Test
        void createKeepsARenamedKeyWhenTheInstrumentationNameIsAlsoPresent() {
            var usage = usage("prompt_tokens", 10, "input_tokens", 7, "output_tokens", 3);

            var spanUsage = SpanUsageFactory.create("anthropic", usage, OTEL_RENAMED);

            assertThat(spanUsage.original().keys()).isEqualTo(usage);
            assertThat(spanUsage.nativeUsage()).isEqualTo(AnthropicNativeUsage.builder().provider("anthropic")
                    .variant("anthropic_native").inputTokens(7).outputTokens(3).build());
        }

        @Test
        void createIgnoresTheHintForUsageTheSdkAlreadyConverted() {
            var usage = usage("prompt_tokens", 280, "completion_tokens", 100, "total_tokens", 380,
                    "original_usage.prompt_tokens", 250, "original_usage.completion_tokens", 100,
                    "original_usage.cache_read_input_tokens", 30);

            var withHint = SpanUsageFactory.create("anthropic", usage, OTEL_RENAMED);
            var withoutHint = SpanUsageFactory.create("anthropic", usage, UsageContext.NONE);

            assertThat(withHint).isEqualTo(withoutHint);
        }

        @Test
        void createWithoutProviderKeepsTheNamesAsSent() {
            var spanUsage = SpanUsageFactory.create(null, RENAMED_ANTHROPIC, OTEL_RENAMED);

            assertThat(spanUsage.original().keys()).isEqualTo(RENAMED_ANTHROPIC);
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage("prompt_tokens", 2000, "completion_tokens", 500,
                    "total_tokens", 2500, "cache_read_input_tokens", 10000));
        }
    }

    @Nested
    class StoredUsage {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.comet.opik.domain.token_usage.SpanUsageFactoryTest#sdkConvertedCases")
        void toUsageMapKeepsWhatTheSdkSentWhenTheTotalsAgree(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE);

            assertThat(spanUsage.toUsageMap()).isEqualTo(usageCase.usage());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void createFromTheStoredUsageGivesTheSameUsage(UsageCase usageCase) {
            var first = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE);

            var second = SpanUsageFactory.create(usageCase.provider(), first.toUsageMap(), UsageContext.NONE);

            assertThat(second.nativeUsage()).isEqualTo(first.nativeUsage());
            assertThat(second.openAiLike()).isEqualTo(first.openAiLike());
            assertThat(second.toUsageMap()).isEqualTo(first.toUsageMap());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void toUsageMapKeepsEveryOriginalKey(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE);

            var stored = OriginalUsage.from(spanUsage.toUsageMap());

            assertThat(stored.keys()).isEqualTo(spanUsage.original().keys());
        }

        @Test
        void toUsageMapMovesRawProviderKeysUnderOriginalUsage() {
            var spanUsage = SpanUsageFactory.create("anthropic",
                    usage("input_tokens", 200, "output_tokens", 100, "cache_read_input_tokens", 30), UsageContext.NONE);

            assertThat(spanUsage.toUsageMap()).isEqualTo(usage("original_usage.input_tokens", 200,
                    "original_usage.output_tokens", 100, "original_usage.cache_read_input_tokens", 30,
                    "prompt_tokens", 230, "completion_tokens", 100, "total_tokens", 330));
        }

        @Test
        void toUsageMapKeepsUsageWithOnlyTheTotalsAtTheTopLevel() {
            var usage = usage("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30);

            var spanUsage = SpanUsageFactory.create("mistral", usage, UsageContext.NONE);

            assertThat(spanUsage.toUsageMap()).isEqualTo(usage);
        }

        @Test
        void toUsageMapReplacesClientTotalsThatDisagreeWithTheProviderUsage() {
            var spanUsage = SpanUsageFactory.create("anthropic",
                    usage("prompt_tokens", 200, "completion_tokens", 100, "total_tokens", 300,
                            "original_usage.input_tokens", 200, "original_usage.output_tokens", 100,
                            "original_usage.cache_read_input_tokens", 30),
                    UsageContext.NONE);

            assertThat(spanUsage.toUsageMap()).isEqualTo(usage("prompt_tokens", 230, "completion_tokens", 100,
                    "total_tokens", 330, "original_usage.input_tokens", 200, "original_usage.output_tokens", 100,
                    "original_usage.cache_read_input_tokens", 30));
        }

        @Test
        void toUsageMapKeepsOtherKeysTheClientSentNextToOriginalUsage() {
            var usage = usage("prompt_tokens", 7, "completion_tokens", 3, "total_tokens", 10, "requests", 2,
                    "original_usage.input_tokens", 7, "original_usage.output_tokens", 3);

            var spanUsage = SpanUsageFactory.create("openai", usage, UsageContext.NONE);

            assertThat(spanUsage.toUsageMap()).isEqualTo(usage);
        }

        @Test
        void toUsageMapAddsTheThoughtsLangChainLeavesOutOfGeminiTotals() {
            // A production sample: LangChain reports candidates and thoughts separately but leaves the thoughts out of
            // its completion and total.
            var usage = usage("prompt_tokens", 3091, "completion_tokens", 8370, "total_tokens", 11461,
                    "original_usage.prompt_token_count", 3091, "original_usage.candidates_token_count", 8370,
                    "original_usage.thoughts_token_count", 6958, "original_usage.total_token_count", 11461,
                    "original_usage.cached_content_token_count", 0);

            var spanUsage = SpanUsageFactory.create("gemini", usage, UsageContext.NONE);

            assertThat(spanUsage.openAiLike()).isEqualTo(OpenAiLikeUsage.of(3091, 15328, 18419, 0));
            assertThat(spanUsage.toUsageMap()).isEqualTo(usage("prompt_tokens", 3091, "completion_tokens", 15328,
                    "total_tokens", 18419, "original_usage.prompt_token_count", 3091,
                    "original_usage.candidates_token_count", 8370, "original_usage.thoughts_token_count", 6958,
                    "original_usage.total_token_count", 11461, "original_usage.cached_content_token_count", 0));
        }

        @Test
        void createFromUsageConvertedTwiceReadsTheDeepestLevelAndStoresItOnce() {
            var usage = usage("prompt_tokens", 230, "completion_tokens", 100, "total_tokens", 330,
                    "original_usage.prompt_tokens", 230, "original_usage.completion_tokens", 100,
                    "original_usage.total_tokens", 330,
                    "original_usage.original_usage.input_tokens", 200,
                    "original_usage.original_usage.output_tokens", 100,
                    "original_usage.original_usage.cache_read_input_tokens", 30);

            var spanUsage = SpanUsageFactory.create("anthropic", usage, UsageContext.NONE);

            assertThat(spanUsage.nativeUsage()).isEqualTo(AnthropicNativeUsage.builder().provider("anthropic")
                    .variant("anthropic_native").inputTokens(200).cacheReadInputTokens(30).outputTokens(100).build());
            var stored = usage("prompt_tokens", 230, "completion_tokens", 100, "total_tokens", 330,
                    "original_usage.input_tokens", 200, "original_usage.output_tokens", 100,
                    "original_usage.cache_read_input_tokens", 30);
            assertThat(spanUsage.toUsageMap()).isEqualTo(stored);
            assertThat(SpanUsageFactory.create("anthropic", stored, UsageContext.NONE).toUsageMap()).isEqualTo(stored);
        }
    }

    @Nested
    class Invariants {

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void openAiLikeCountsAreNonNegative(UsageCase usageCase) {
            var openAiLike = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE)
                    .openAiLike();

            assertThat(List.of(openAiLike.promptTokens(), openAiLike.completionTokens(), openAiLike.totalTokens(),
                    openAiLike.cachedPromptTokens())).allSatisfy(count -> assertThat(count).isNotNegative());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void openAiLikeCachedTokensAreWithinThePrompt(UsageCase usageCase) {
            var openAiLike = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE)
                    .openAiLike();

            assertThat(openAiLike.cachedPromptTokens()).isLessThanOrEqualTo(openAiLike.promptTokens());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void openAiLikeTotalIsPromptPlusCompletion(UsageCase usageCase) {
            var openAiLike = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE)
                    .openAiLike();

            assertThat(openAiLike.totalTokens()).isEqualTo(openAiLike.promptTokens() + openAiLike.completionTokens());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void nativeUsageCarriesTheProvider(UsageCase usageCase) {
            var spanUsage = SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), UsageContext.NONE);

            assertThat(spanUsage.nativeUsage().provider()).isEqualTo(usageCase.provider());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(ALL_CASES)
        void createDoesNotModifyTheUsageItReceives(UsageCase usageCase) {
            var usage = Map.copyOf(usageCase.usage());

            SpanUsageFactory.create(usageCase.provider(), usageCase.usage(), OTEL_RENAMED);

            assertThat(usageCase.usage()).isEqualTo(usage);
        }
    }

    static Stream<UsageCase> sdkConvertedCases() {
        return UsageCases.all()
                .filter(usageCase -> usageCase.usage().keySet().stream()
                        .anyMatch(key -> key.startsWith(ORIGINAL_USAGE_PREFIX)));
    }
}
