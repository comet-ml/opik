package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EvalSuiteEvaluatorMapper Test")
class EvalSuiteEvaluatorMapperTest {

    @Nested
    @DisplayName("resolveModel")
    class ResolveModel {

        @Test
        @DisplayName("returns empty when no supported provider is connected")
        void returnsEmptyWhenNoSupportedProvider() {
            var result = EvalSuiteEvaluatorMapper.resolveModel(Set.of());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty when only unsupported providers are connected")
        void returnsEmptyWhenOnlyUnsupportedProviders() {
            var result = EvalSuiteEvaluatorMapper.resolveModel(
                    Set.of(LlmProvider.OLLAMA, LlmProvider.BEDROCK, LlmProvider.OPEN_ROUTER));
            assertThat(result).isEmpty();
        }

        @ParameterizedTest(name = "{0} resolves to {1}")
        @MethodSource("singleProviderCases")
        @DisplayName("resolves correct model for single supported provider")
        void resolvesModelForSingleProvider(LlmProvider provider, String expectedModel) {
            var result = EvalSuiteEvaluatorMapper.resolveModel(Set.of(provider));
            assertThat(result).hasValue(expectedModel);
        }

        static Stream<Arguments> singleProviderCases() {
            return Stream.of(
                    Arguments.of(LlmProvider.OPEN_AI, "gpt-5-nano"),
                    Arguments.of(LlmProvider.ANTHROPIC, "claude-haiku-4-5-20251001"),
                    Arguments.of(LlmProvider.GEMINI, "gemini-2.0-flash"));
        }

        @Test
        @DisplayName("picks OpenAI over Anthropic and Gemini when all are connected")
        void picksHighestPriorityProvider() {
            var result = EvalSuiteEvaluatorMapper.resolveModel(
                    Set.of(LlmProvider.GEMINI, LlmProvider.ANTHROPIC, LlmProvider.OPEN_AI));
            assertThat(result).hasValue("gpt-5-nano");
        }

        @Test
        @DisplayName("picks Anthropic over Gemini when OpenAI is not connected")
        void picksAnthropicOverGemini() {
            var result = EvalSuiteEvaluatorMapper.resolveModel(
                    Set.of(LlmProvider.GEMINI, LlmProvider.ANTHROPIC));
            assertThat(result).hasValue("claude-haiku-4-5-20251001");
        }

        @Test
        @DisplayName("ignores unsupported providers in mixed set")
        void ignoresUnsupportedProvidersInMixedSet() {
            var result = EvalSuiteEvaluatorMapper.resolveModel(
                    Set.of(LlmProvider.OLLAMA, LlmProvider.BEDROCK, LlmProvider.GEMINI));
            assertThat(result).hasValue("gemini-2.0-flash");
        }
    }
}
