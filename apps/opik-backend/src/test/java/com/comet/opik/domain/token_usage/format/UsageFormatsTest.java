package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.cost.PriceFileProviders;
import com.comet.opik.domain.token_usage.UsageCases;
import com.comet.opik.domain.token_usage.model.AnthropicNativeUsage;
import com.comet.opik.domain.token_usage.model.BedrockConverseNativeUsage;
import com.comet.opik.domain.token_usage.model.GeminiNativeUsage;
import com.comet.opik.domain.token_usage.model.NativeUsage;
import com.comet.opik.domain.token_usage.model.OpenAiNativeUsage;
import com.comet.opik.domain.token_usage.model.OriginalUsage;
import com.comet.opik.domain.token_usage.model.UsageWarning;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.comet.opik.domain.token_usage.UsageCases.usage;
import static org.assertj.core.api.Assertions.assertThat;

class UsageFormatsTest {

    private final List<UsageWarning> warnings = new ArrayList<>();

    private NativeUsage normalize(String provider, Map<String, Integer> usage) {
        return UsageFormats.normalize(provider, OriginalUsage.from(usage), warnings).orElseThrow();
    }

    @Nested
    class FormatByProvider {

        @ParameterizedTest
        @CsvSource({
                "anthropic, ANTHROPIC",
                "vertex_ai-anthropic_models, ANTHROPIC",
                "gemini, GEMINI",
                "vertex_ai-language-models, GEMINI",
                "vertex_ai, GEMINI",
                "bedrock, BEDROCK_CONVERSE",
                "bedrock_converse, BEDROCK_CONVERSE",
                "openai, OPENAI",
                "azure, OPENAI",
                "deepseek, OPENAI",
                "xai, OPENAI",
                "mistral, OPENAI",
                "groq, OPENAI",
                "openrouter, OPENAI",
                "vertex_ai-mistral_models, OPENAI",
                "my-gateway, OPENAI"})
        void forProvider(String provider, String format) throws ReflectiveOperationException {
            var expected = UsageFormats.class.getDeclaredField(format).get(null);

            assertThat(UsageFormats.forProvider(provider)).isSameAs(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Anthropic", " anthropic", "google_ai", "google_vertexai", "anthropic_vertexai"})
        void forProviderTakesOnlyResolvedLiteLlmNames(String provider) {
            assertThat(UsageFormats.forProvider(provider)).isSameAs(UsageFormats.OPENAI);
        }

        @Test
        void everyProviderWithItsOwnFormatIsAProviderOfThePriceFile() {
            assertThat(PriceFileProviders.ALL).containsAll(UsageFormats.providersWithOwnFormat());
        }

        @Test
        void allListsEveryFormatOnce() {
            assertThat(UsageFormats.ALL).containsExactlyInAnyOrder(UsageFormats.ANTHROPIC, UsageFormats.OPENAI,
                    UsageFormats.GEMINI, UsageFormats.BEDROCK_CONVERSE);
        }
    }

    @Nested
    class InputVariants {

        @Test
        void variantNamesAreUniqueWithinAFormat() {
            assertThat(UsageFormats.ALL).allSatisfy(format -> assertThat(format.inputVariants())
                    .extracting(InputVariant::name)
                    .doesNotHaveDuplicates());
        }

        @Test
        void everyVariantHasAUsageCase() {
            var covered = UsageCases.all()
                    .map(usageCase -> UsageFormats.forProvider(usageCase.provider()) + "/"
                            + usageCase.expected().variant())
                    .collect(Collectors.toSet());

            var registered = UsageFormats.ALL.stream()
                    .flatMap(format -> format.inputVariants().stream().map(variant -> format + "/" + variant.name()))
                    .toList();

            assertThat(covered).containsAll(registered);
        }

        @Test
        void everyUsageCaseMatchesARegisteredVariant() {
            assertThat(UsageCases.all()).allSatisfy(usageCase -> assertThat(
                    UsageFormats.forProvider(usageCase.provider()).inputVariants())
                    .extracting(InputVariant::name)
                    .contains(usageCase.expected().variant()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"anthropic", "openai", "gemini", "bedrock"})
        void normalizeReturnsEmptyWhenNoVariantMatches(String provider) {
            assertThat(UsageFormats.normalize(provider, OriginalUsage.from(usage("characters", 1200)), warnings))
                    .isEqualTo(Optional.empty());
            assertThat(UsageFormats.normalize(provider, OriginalUsage.EMPTY, warnings)).isEqualTo(Optional.empty());
            assertThat(warnings).isEmpty();
        }
    }

    @Nested
    class Anthropic {

        private AnthropicNativeUsage.AnthropicNativeUsageBuilder expected(String variant) {
            return AnthropicNativeUsage.builder().provider("anthropic").variant(variant);
        }

        @Test
        void otelGenAiComesBeforeNativeBecauseItsInputIncludesTheCache() {
            var nativeUsage = normalize("anthropic", usage("input_tokens", 12000, "output_tokens", 500,
                    "cache_read.input_tokens", 10000, "cache_read_input_tokens", 10000));

            assertThat(nativeUsage).isEqualTo(expected("otel_genai").inputTokens(2000).cacheReadInputTokens(10000)
                    .outputTokens(500).build());
        }

        @Test
        void otelGenAiWithInputBelowItsCacheReportsItAndTakesItAsExcludingTheCache() {
            var nativeUsage = normalize("anthropic", usage("input_tokens", 100, "output_tokens", 5,
                    "cache_creation.input_tokens", 300));

            assertThat(nativeUsage).isEqualTo(expected("otel_genai").inputTokens(100).cacheCreation5mInputTokens(300)
                    .outputTokens(5).build());
            assertThat(warnings).containsExactly(UsageWarning.cacheExceedsInput("anthropic", "otel_genai", 100, 300));
        }

        @Test
        void nativeWithIterationsButNoTopLevelCounts() {
            var nativeUsage = normalize("anthropic", usage("iterations.0.input_tokens", 10,
                    "iterations.0.output_tokens", 2, "iterations.1.input_tokens", 5, "iterations.1.output_tokens", 1));

            assertThat(nativeUsage).isEqualTo(expected("anthropic_native").inputTokens(15).outputTokens(3).build());
        }

        @Test
        void nativeCountsAnUnsplitCacheWriteAsFiveMinutes() {
            var nativeUsage = normalize("anthropic", usage("input_tokens", 10, "cache_creation_input_tokens", 300));

            assertThat(nativeUsage).isEqualTo(expected("anthropic_native").inputTokens(10)
                    .cacheCreation5mInputTokens(300).build());
        }

        // Production: the TTL split accounted for 2,017 of the 9,818 tokens written; the rest were written all the same.
        @Test
        void nativeCountsWritesTheTtlSplitLeavesOutAsFiveMinutes() {
            var nativeUsage = normalize("anthropic", usage("input_tokens", 123, "output_tokens", 1477,
                    "cache_read_input_tokens", 81131, "cache_creation_input_tokens", 9818,
                    "cache_creation.ephemeral_5m_input_tokens", 0, "cache_creation.ephemeral_1h_input_tokens", 2017));

            assertThat(nativeUsage).isEqualTo(expected("anthropic_native").inputTokens(123)
                    .cacheReadInputTokens(81131).cacheCreation5mInputTokens(7801).cacheCreation1hInputTokens(2017)
                    .outputTokens(1477).build());
            assertThat(nativeUsage.toOpenAiLike().promptTokens()).isEqualTo(91072);
        }

        @Test
        void nativeTakesTheTtlSplitInsteadOfTheTotalCacheWrite() {
            var nativeUsage = normalize("anthropic", usage("input_tokens", 10, "cache_creation_input_tokens", 300,
                    "cache_creation.ephemeral_1h_input_tokens", 300));

            assertThat(nativeUsage).isEqualTo(expected("anthropic_native").inputTokens(10)
                    .cacheCreation1hInputTokens(300).build());
        }

        @Test
        void liteLlmWithOnlyACacheWriteExcludesItFromTheInput() {
            var nativeUsage = normalize("anthropic", usage("prompt_tokens", 1300, "completion_tokens", 5,
                    "cache_creation_input_tokens", 1000, "prompt_tokens_details.cached_tokens", 0,
                    "prompt_tokens_details.cache_creation_tokens", 1000));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(300).cacheCreation5mInputTokens(1000)
                    .outputTokens(5).build());
        }

        // OTel ingestion and the online-evaluation judge send Anthropic's input_tokens, which excludes the cache, as
        // prompt_tokens; only LiteLLM details its prompt. A total of prompt + completion doesn't tell them apart.
        @Test
        void promptTokensWithoutLiteLlmDetailsAreAnthropicsInput() {
            var nativeUsage = normalize("anthropic", usage("prompt_tokens", 1300, "completion_tokens", 5,
                    "total_tokens", 1305, "cache_read_input_tokens", 200, "cache_creation_input_tokens", 1000));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(1300).cacheReadInputTokens(200)
                    .cacheCreation5mInputTokens(1000).outputTokens(5).build());
            assertThat(warnings).isEmpty();
        }

        // Production (Pydantic AI's names): the total shows the cache is outside the prompt.
        @Test
        void liteLlmReadsTheShortCacheNames() {
            var nativeUsage = normalize("anthropic", usage("prompt_tokens", 6, "completion_tokens", 874,
                    "total_tokens", 208992, "cache_read_tokens", 135324, "cache_write_tokens", 72788));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(6).cacheReadInputTokens(135324)
                    .cacheCreation5mInputTokens(72788).outputTokens(874).build());
            assertThat(nativeUsage.toOpenAiLike().totalTokens()).isEqualTo(208992);
        }

        // Production (Claude Code's names): a total that leaves the cache out, like the prompt does.
        @Test
        void liteLlmReadsClaudeCodeCacheNames() {
            var nativeUsage = normalize("anthropic", usage("prompt_tokens", 615, "completion_tokens", 2062,
                    "total_tokens", 2677, "cache_read_tokens", 116079, "cache_creation_tokens", 19912));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(615).cacheReadInputTokens(116079)
                    .cacheCreation5mInputTokens(19912).outputTokens(2062).build());
        }

        @Test
        void liteLlmPrefersTheCacheReadCounterOverThePromptDetails() {
            var nativeUsage = normalize("anthropic", usage("prompt_tokens", 1500, "completion_tokens", 50,
                    "cache_read_input_tokens", 1000, "prompt_tokens_details.cached_tokens", 900));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(500).cacheReadInputTokens(1000)
                    .outputTokens(50).build());
        }

        @Test
        void totalsOnlyWithJustTheCompletion() {
            assertThat(normalize("anthropic", usage("completion_tokens", 40)))
                    .isEqualTo(expected("totals_only").outputTokens(40).build());
        }
    }

    @Nested
    class OpenAi {

        private OpenAiNativeUsage.OpenAiNativeUsageBuilder expected(String variant) {
            return OpenAiNativeUsage.builder().provider("openai").variant(variant);
        }

        @Test
        void deepSeekWithOnlyCacheMisses() {
            var nativeUsage = normalize("openai", usage("prompt_tokens", 100, "completion_tokens", 5,
                    "prompt_cache_miss_tokens", 100));

            assertThat(nativeUsage).isEqualTo(expected("deepseek").promptTokens(100).completionTokens(5).build());
        }

        @Test
        void chatCompletionsPrefersThePromptDetailsForTheCache() {
            var nativeUsage = normalize("openai", usage("prompt_tokens", 100, "completion_tokens", 5,
                    "prompt_tokens_details.cached_tokens", 30, "cache_read_input_tokens", 40, "cached_tokens", 50));

            assertThat(nativeUsage).isEqualTo(expected("chat_completions").promptTokens(100).cachedTokens(30)
                    .completionTokens(5).build());
        }

        @Test
        void chatCompletionsPrefersTheCompletionDetailsForReasoning() {
            var nativeUsage = normalize("openai", usage("prompt_tokens", 100, "completion_tokens", 50,
                    "completion_tokens_details.reasoning_tokens", 30, "reasoning_tokens", 40));

            assertThat(nativeUsage).isEqualTo(expected("chat_completions").promptTokens(100).completionTokens(50)
                    .reasoningTokens(30).build());
        }

        @Test
        void chatCompletionsComesBeforeResponsesWhenBothNamesAreSent() {
            var nativeUsage = normalize("openai", usage("prompt_tokens", 100, "input_tokens", 90,
                    "completion_tokens", 5));

            assertThat(nativeUsage).isEqualTo(expected("chat_completions").promptTokens(100).completionTokens(5)
                    .build());
        }

        @Test
        void responsesWithAudio() {
            var nativeUsage = normalize("openai", usage("input_tokens", 100, "output_tokens", 50,
                    "input_tokens_details.audio_tokens", 20, "output_tokens_details.audio_tokens", 10));

            assertThat(nativeUsage).isEqualTo(expected("responses").promptTokens(100).audioPromptTokens(20)
                    .completionTokens(50).audioCompletionTokens(10).build());
        }

        @Test
        void responsesWithCacheAboveTheInputReportsItAndAddsTheCache() {
            var nativeUsage = normalize("openai", usage("input_tokens", 100, "input_tokens_details.cached_tokens",
                    300));

            assertThat(nativeUsage).isEqualTo(expected("responses").promptTokens(400).cachedTokens(300).build());
            assertThat(warnings).containsExactly(UsageWarning.cacheExceedsInput("openai", "responses", 100, 300));
        }

        // Production (nous through LiteLLM): a prompt below its cache leaves it out, and so does the reported total.
        @Test
        void chatCompletionsWithAPromptBelowItsCacheAddsTheCacheToThePromptAndTheTotal() {
            var nativeUsage = normalize("openai", usage("prompt_tokens", 170, "completion_tokens", 78,
                    "total_tokens", 248, "cache_read_input_tokens", 31232));

            assertThat(nativeUsage).isEqualTo(expected("chat_completions").promptTokens(31402).cachedTokens(31232)
                    .completionTokens(78).totalTokens(248).build());
            assertThat(nativeUsage.toOpenAiLike().totalTokens()).isEqualTo(31480);
        }

        @Test
        void chatCompletionsWhoseTotalAddsTheCacheOnTopOfThePromptAddsIt() {
            var nativeUsage = normalize("openai", usage("prompt_tokens", 1000, "completion_tokens", 100,
                    "total_tokens", 1400, "prompt_tokens_details.cached_tokens", 300));

            assertThat(nativeUsage).isEqualTo(expected("chat_completions").promptTokens(1300).cachedTokens(300)
                    .completionTokens(100).totalTokens(1400).build());
            assertThat(warnings).isEmpty();
        }

        // Production (gpt-5.6-sol): the total adds up with the cache inside the input.
        @Test
        void responsesWhoseTotalAddsUpWithoutTheCacheKeepsItInsideTheInput() {
            var nativeUsage = normalize("openai", usage("input_tokens", 897182, "output_tokens", 3684,
                    "total_tokens", 900866, "reasoning_tokens", 542, "cache_read_input_tokens", 836352));

            assertThat(nativeUsage).isEqualTo(expected("responses").promptTokens(897182).cachedTokens(836352)
                    .completionTokens(3684).reasoningTokens(542).totalTokens(900866).build());
        }

        // Production (Mistral's Anthropic-style usage, logged by the SDK): only the SDK's total shows the cache is out.
        @Test
        void responsesReadsTheTotalTheSdkLoggedNextToTheProviderUsage() {
            var nativeUsage = UsageFormats.normalize("mistral", OriginalUsage.from(usage("prompt_tokens", 14835,
                    "completion_tokens", 103, "total_tokens", 14938, "original_usage.input_tokens", 14435,
                    "original_usage.output_tokens", 103, "original_usage.cache_read_input_tokens", 400,
                    "original_usage.cache_creation_input_tokens", 0)), warnings).orElseThrow();

            assertThat(nativeUsage.toOpenAiLike().promptTokens()).isEqualTo(14835);
            assertThat(nativeUsage.toOpenAiLike().totalTokens()).isEqualTo(14938);
        }

        // OpenRouter reports Anthropic's cache writes, which bill at their own rate, in the prompt details.
        @Test
        void chatCompletionsWithCacheWrites() {
            var nativeUsage = normalize("openai", usage("prompt_tokens", 1250, "completion_tokens", 100,
                    "prompt_tokens_details.cached_tokens", 200, "prompt_tokens_details.cache_write_tokens", 50));

            assertThat(nativeUsage).isEqualTo(expected("chat_completions").promptTokens(1250).cachedTokens(200)
                    .cacheWriteTokens(50).completionTokens(100).build());
        }

        // Production (OpenRouter through LiteLLM): the writes are left out of a prompt smaller than them.
        @Test
        void chatCompletionsWithCacheWritesAboveThePromptAddsThem() {
            var nativeUsage = normalize("openai", usage("prompt_tokens", 4, "completion_tokens", 51,
                    "total_tokens", 55, "cache_creation_input_tokens", 22574));

            assertThat(nativeUsage).isEqualTo(expected("chat_completions").promptTokens(22578)
                    .cacheWriteTokens(22574).completionTokens(51).totalTokens(55).build());
        }

        @Test
        void openInferenceWithAudio() {
            var nativeUsage = normalize("openai", usage("llm.token_count.prompt", 100,
                    "llm.token_count.completion", 50, "llm.token_count.prompt_details.audio", 20,
                    "llm.token_count.completion_details.audio", 10));

            assertThat(nativeUsage).isEqualTo(expected("openinference").promptTokens(100).audioPromptTokens(20)
                    .completionTokens(50).audioCompletionTokens(10).build());
        }
    }

    @Nested
    class Gemini {

        private GeminiNativeUsage.GeminiNativeUsageBuilder expected(String variant) {
            return GeminiNativeUsage.builder().provider("gemini").variant(variant);
        }

        @Test
        void nativeComesBeforeTheOpenAiStyle() {
            var nativeUsage = normalize("gemini", usage("prompt_token_count", 200, "prompt_tokens", 999,
                    "candidates_token_count", 100));

            assertThat(nativeUsage).isEqualTo(expected("gemini_native").promptTokenCount(200)
                    .candidatesTokenCount(100).build());
        }

        // Production: the cache is within the prompt and the tool results together, as Gemini's total shows.
        @Test
        void nativeTakesTheCacheAsPartOfThePromptAndTheToolResults() {
            var nativeUsage = normalize("gemini", usage("prompt_token_count", 6063, "tool_use_prompt_token_count", 6063,
                    "cached_content_token_count", 11511, "candidates_token_count", 430, "thoughts_token_count", 8605,
                    "total_token_count", 21161));

            assertThat(nativeUsage.toOpenAiLike().promptTokens()).isEqualTo(12126);
            assertThat(warnings).isEmpty();
        }

        @Test
        void nativeWithoutAPromptTakesTheCacheAsIt() {
            var nativeUsage = normalize("gemini", usage("cached_content_token_count", 16384,
                    "candidates_token_count", 15201));

            assertThat(nativeUsage.toOpenAiLike().promptTokens()).isEqualTo(16384);
        }

        @Test
        void nativeWithCacheAboveThePromptReportsIt() {
            normalize("gemini", usage("prompt_token_count", 10, "cached_content_token_count", 20));

            assertThat(warnings).containsExactly(UsageWarning.cacheExceedsInput("gemini", "gemini_native", 10, 20));
        }

        @Test
        void openAiStyleCapsReasoningAtTheCompletion() {
            var nativeUsage = normalize("gemini", usage("prompt_tokens", 100, "completion_tokens", 50,
                    "reasoning_tokens", 80));

            assertThat(nativeUsage).isEqualTo(expected("openai_style").promptTokenCount(100)
                    .candidatesTokenCount(0).thoughtsTokenCount(50).build());
        }

        @Test
        void openAiStyleKeepsTheOpenAiLikeTotals() {
            var nativeUsage = normalize("gemini", usage("prompt_tokens", 4000, "completion_tokens", 1500,
                    "total_tokens", 5500, "completion_tokens_details.reasoning_tokens", 900));

            assertThat(nativeUsage.toOpenAiLike().completionTokens()).isEqualTo(1500);
            assertThat(nativeUsage.toOpenAiLike().totalTokens()).isEqualTo(5500);
        }

        @Test
        void responsesStyleWithCacheAboveTheInputReportsIt() {
            normalize("gemini", usage("input_tokens", 10, "input_tokens_details.cached_tokens", 20));

            assertThat(warnings).containsExactly(UsageWarning.cacheExceedsInput("gemini", "responses_style", 10, 20));
        }
    }

    @Nested
    class BedrockConverse {

        private BedrockConverseNativeUsage.BedrockConverseNativeUsageBuilder expected(String variant) {
            return BedrockConverseNativeUsage.builder().provider("bedrock").variant(variant);
        }

        @Test
        void conversePrefersTheCacheWriteCounter() {
            var nativeUsage = normalize("bedrock", usage("inputTokens", 10, "cacheWriteInputTokens", 50,
                    "cache_creation_input_tokens", 60));

            assertThat(nativeUsage).isEqualTo(expected("converse").inputTokens(10).cacheWriteInputTokens(50).build());
        }

        @Test
        void converseWithOnlyTheOutput() {
            assertThat(normalize("bedrock", usage("outputTokens", 3)))
                    .isEqualTo(expected("converse").outputTokens(3).build());
        }

        // Production (LiteLLM): the total shows this prompt includes the cache it's reported next to.
        @Test
        void liteLlmWhoseTotalAddsUpWithTheCacheInsideThePromptExcludesIt() {
            var nativeUsage = normalize("bedrock", usage("prompt_tokens", 14805, "completion_tokens", 190,
                    "total_tokens", 14995, "cache_creation_input_tokens", 14802, "cache_read_input_tokens", 0,
                    "prompt_tokens_details.cache_creation_tokens", 14802));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(3).cacheWriteInputTokens(14802)
                    .outputTokens(190).build());
        }

        @Test
        void liteLlmWhoseTotalAddsTheCacheOnTopOfThePromptKeepsIt() {
            var nativeUsage = normalize("bedrock", usage("prompt_tokens", 10247, "completion_tokens", 329,
                    "total_tokens", 34270, "cache_creation_input_tokens", 23694, "cache_read_input_tokens", 0));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(10247).cacheWriteInputTokens(23694)
                    .outputTokens(329).build());
        }

        @Test
        void liteLlmWithoutATotalKeepsThePromptAsExcludingTheCache() {
            var nativeUsage = normalize("bedrock", usage("prompt_tokens", 1000, "completion_tokens", 100,
                    "cache_read_input_tokens", 200));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(1000).cacheReadInputTokens(200)
                    .outputTokens(100).build());
            assertThat(warnings).isEmpty();
        }

        @Test
        void geminiShapedUsageIsNotLlama() {
            var usage = usage("prompt_token_count", 55659, "candidates_token_count", 438);

            assertThat(UsageFormats.normalize("bedrock", OriginalUsage.from(usage), warnings)).isEmpty();
        }

        @Test
        void converseComesBeforeLlama() {
            var nativeUsage = normalize("bedrock", usage("inputTokens", 10, "prompt_token_count", 99));

            assertThat(nativeUsage).isEqualTo(expected("converse").inputTokens(10).build());
        }

        @Test
        void promptTokensWithoutCacheAreTotalsOnly() {
            var nativeUsage = normalize("bedrock", usage("prompt_tokens", 10, "completion_tokens", 20,
                    "prompt_tokens_details.cached_tokens", 0));

            assertThat(nativeUsage).isEqualTo(expected("totals_only").inputTokens(10).outputTokens(20).build());
        }
    }
}
