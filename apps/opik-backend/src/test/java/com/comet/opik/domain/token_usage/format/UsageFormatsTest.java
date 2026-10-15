package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.cost.CostService;
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
            assertThat(CostService.providerRegistry().providers())
                    .containsAll(UsageFormats.providersWithOwnFormat());
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
                    "cache_creation_input_tokens", 1000));

            assertThat(nativeUsage).isEqualTo(expected("litellm").inputTokens(300).cacheCreation5mInputTokens(1000)
                    .outputTokens(5).build());
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
        void responsesWithCacheAboveTheInputReportsItAndKeepsTheProviderValues() {
            var nativeUsage = normalize("openai", usage("input_tokens", 100, "input_tokens_details.cached_tokens",
                    300));

            assertThat(nativeUsage).isEqualTo(expected("responses").promptTokens(100).cachedTokens(300).build());
            assertThat(warnings).containsExactly(UsageWarning.cacheExceedsInput("openai", "responses", 100, 300));
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
