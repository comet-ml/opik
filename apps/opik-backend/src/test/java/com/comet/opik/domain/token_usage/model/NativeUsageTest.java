package com.comet.opik.domain.token_usage.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeUsageTest {

    @Nested
    class Anthropic {

        private final AnthropicNativeUsage.AnthropicNativeUsageBuilder usage = AnthropicNativeUsage.builder()
                .provider("anthropic").variant("anthropic_native");

        @Test
        void toOpenAiLikeWithoutCache() {
            assertThat(usage.inputTokens(200).outputTokens(100).build().toOpenAiLike())
                    .isEqualTo(OpenAiLikeUsage.of(200, 100, 300, 0));
        }

        @Test
        void toOpenAiLikeAddsTheCacheToThePrompt() {
            var nativeUsage = usage.inputTokens(100).cacheReadInputTokens(20).cacheCreation5mInputTokens(300)
                    .cacheCreation1hInputTokens(4000).outputTokens(10).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(4420, 10, 4430, 20));
        }

        @Test
        void toOpenAiLikeWhenEverythingIsCached() {
            assertThat(usage.cacheReadInputTokens(5000).outputTokens(1).build().toOpenAiLike())
                    .isEqualTo(OpenAiLikeUsage.of(5000, 1, 5001, 5000));
        }
    }

    @Nested
    class OpenAi {

        private final OpenAiNativeUsage.OpenAiNativeUsageBuilder usage = OpenAiNativeUsage.builder()
                .provider("openai").variant("chat_completions");

        @Test
        void toOpenAiLikeKeepsThePromptBecauseItIncludesTheCache() {
            var nativeUsage = usage.promptTokens(1500).cachedTokens(1000).audioPromptTokens(10).completionTokens(300)
                    .reasoningTokens(200).audioCompletionTokens(20).totalTokens(1800).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(1500, 300, 1800, 1000));
        }

        @Test
        void toOpenAiLikeKeepsTheReportedTotal() {
            assertThat(usage.promptTokens(10).completionTokens(20).totalTokens(35).build().toOpenAiLike())
                    .isEqualTo(OpenAiLikeUsage.of(10, 20, 35, 0));
        }

        @Test
        void toOpenAiLikeWithoutReportedTotalAddsPromptAndCompletion() {
            assertThat(usage.promptTokens(10).completionTokens(20).build().toOpenAiLike())
                    .isEqualTo(OpenAiLikeUsage.of(10, 20, 30, 0));
        }

        @Test
        void toOpenAiLikeWhenEmpty() {
            assertThat(usage.build().toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(0, 0, 0, 0));
        }
    }

    @Nested
    class Gemini {

        private final GeminiNativeUsage.GeminiNativeUsageBuilder usage = GeminiNativeUsage.builder()
                .provider("gemini").variant("gemini_native");

        @Test
        void toOpenAiLikeKeepsThePromptBecauseItIncludesTheCache() {
            var nativeUsage = usage.promptTokenCount(200).cachedContentTokenCount(50).candidatesTokenCount(100)
                    .totalTokenCount(300).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(200, 100, 300, 50));
        }

        @Test
        void toOpenAiLikeAddsThoughtsReportedOutsideTheCandidates() {
            var nativeUsage = usage.promptTokenCount(1000).candidatesTokenCount(500).thoughtsTokenCount(500)
                    .totalTokenCount(2000).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(1000, 1000, 2000, 0));
        }

        @Test
        void toOpenAiLikeAddsTheThoughtsEvenWhenTheReportedTotalLeavesThemOut() {
            var nativeUsage = usage.promptTokenCount(3091).candidatesTokenCount(8370).thoughtsTokenCount(6958)
                    .totalTokenCount(11461).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(3091, 15328, 18419, 0));
        }

        @Test
        void toOpenAiLikeCountsToolUseOnThePromptSide() {
            var nativeUsage = usage.promptTokenCount(27).toolUsePromptTokenCount(10309).candidatesTokenCount(45)
                    .thoughtsTokenCount(31).totalTokenCount(10412).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(10336, 76, 10412, 0));
        }

        @Test
        void toOpenAiLikeWithoutReportedTotalAddsThePromptAndAllOutput() {
            var nativeUsage = usage.promptTokenCount(4000).candidatesTokenCount(600).thoughtsTokenCount(900).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(4000, 1500, 5500, 0));
        }

        // Production: the client sent the total and the candidates, but no prompt.
        @Test
        void toOpenAiLikeKeepsAReportedTotalAboveTheCounts() {
            var nativeUsage = usage.candidatesTokenCount(1113).totalTokenCount(4924).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(0, 1113, 4924, 0));
        }

        @Test
        void toOpenAiLikeWithoutCandidates() {
            assertThat(usage.promptTokenCount(7859).totalTokenCount(7859).build().toOpenAiLike())
                    .isEqualTo(OpenAiLikeUsage.of(7859, 0, 7859, 0));
        }
    }

    @Nested
    class BedrockConverse {

        private final BedrockConverseNativeUsage.BedrockConverseNativeUsageBuilder usage = BedrockConverseNativeUsage
                .builder().provider("bedrock").variant("converse");

        @Test
        void toOpenAiLikeWithoutCache() {
            assertThat(usage.inputTokens(200).outputTokens(100).build().toOpenAiLike())
                    .isEqualTo(OpenAiLikeUsage.of(200, 100, 300, 0));
        }

        @Test
        void toOpenAiLikeAddsTheCacheToThePrompt() {
            var nativeUsage = usage.inputTokens(12).cacheReadInputTokens(4096).cacheWriteInputTokens(1024)
                    .outputTokens(7).build();

            assertThat(nativeUsage.toOpenAiLike()).isEqualTo(OpenAiLikeUsage.of(5132, 7, 5139, 4096));
        }
    }

    @Test
    void toOpenAiLikeAgreesForTheSameClaudeCallOnAnthropicAndBedrock() {
        var anthropic = AnthropicNativeUsage.builder().provider("anthropic").variant("anthropic_native")
                .inputTokens(12).cacheReadInputTokens(4096).cacheCreation5mInputTokens(1024).outputTokens(7).build();
        var bedrock = BedrockConverseNativeUsage.builder().provider("bedrock").variant("converse")
                .inputTokens(12).cacheReadInputTokens(4096).cacheWriteInputTokens(1024).outputTokens(7).build();

        assertThat(bedrock.toOpenAiLike()).isEqualTo(anthropic.toOpenAiLike());
    }
}
