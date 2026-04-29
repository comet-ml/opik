package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIModelName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TestSuiteEvaluatorMapper Test")
class TestSuiteEvaluatorMapperTest {

    @Nested
    @DisplayName("resolveModel")
    class ResolveModel {

        @Test
        @DisplayName("returns empty when no supported provider is connected")
        void returnsEmptyWhenNoSupportedProvider() {
            var result = SupportedJudgeProvider.resolveModel(Set.of());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when only unsupported providers are connected")
        void returnsEmptyWhenOnlyUnsupportedProviders() {
            var result = SupportedJudgeProvider.resolveModel(
                    Set.of(LlmProvider.OLLAMA, LlmProvider.BEDROCK, LlmProvider.OPEN_ROUTER));
            assertThat(result).isEmpty();
        }

        @ParameterizedTest(name = "{0} resolves to {1}")
        @MethodSource("singleProviderCases")
        @DisplayName("resolves correct model for single supported provider")
        void resolvesModelForSingleProvider(LlmProvider provider, String expectedModel) {
            var result = SupportedJudgeProvider.resolveModel(Set.of(provider));
            assertThat(result).hasValue(expectedModel);
        }

        static Stream<Arguments> singleProviderCases() {
            return Stream.of(
                    Arguments.of(LlmProvider.OPEN_AI, OpenaiModelName.GPT_5_NANO.toString()),
                    Arguments.of(LlmProvider.ANTHROPIC, AnthropicModelName.CLAUDE_HAIKU_4_5.toString()),
                    Arguments.of(LlmProvider.GEMINI, GeminiModelName.GEMINI_2_0_FLASH.toString()),
                    Arguments.of(LlmProvider.VERTEX_AI, VertexAIModelName.GEMINI_2_5_FLASH.qualifiedName()));
        }

        @Test
        @DisplayName("picks OpenAI over Anthropic and Gemini when all are connected")
        void picksHighestPriorityProvider() {
            var result = SupportedJudgeProvider.resolveModel(
                    Set.of(LlmProvider.GEMINI, LlmProvider.ANTHROPIC, LlmProvider.OPEN_AI));
            assertThat(result).hasValue(OpenaiModelName.GPT_5_NANO.toString());
        }

        @Test
        @DisplayName("picks Anthropic over Gemini when OpenAI is not connected")
        void picksAnthropicOverGemini() {
            var result = SupportedJudgeProvider.resolveModel(
                    Set.of(LlmProvider.GEMINI, LlmProvider.ANTHROPIC));
            assertThat(result).hasValue(AnthropicModelName.CLAUDE_HAIKU_4_5.toString());
        }

        @Test
        @DisplayName("ignores unsupported providers in mixed set")
        void ignoresUnsupportedProvidersInMixedSet() {
            var result = SupportedJudgeProvider.resolveModel(
                    Set.of(LlmProvider.OLLAMA, LlmProvider.BEDROCK, LlmProvider.GEMINI));
            assertThat(result).hasValue(GeminiModelName.GEMINI_2_0_FLASH.toString());
        }
    }
}
