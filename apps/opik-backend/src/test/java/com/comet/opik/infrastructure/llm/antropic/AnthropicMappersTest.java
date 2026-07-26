package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.podam.PodamFactoryUtils;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.shared.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class AnthropicMappersTest {
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AnthropicMappers {
        @Test
        void testToResponse() {
            var response = podamFactory.manufacturePojo(AnthropicCreateMessageResponse.class);

            var actual = LlmProviderAnthropicMapper.INSTANCE.toResponse(response);
            assertThat(actual).isNotNull();
            assertThat(actual.id()).isEqualTo(response.id);
            assertThat(actual.choices()).isEqualTo(List.of(ChatCompletionChoice.builder()
                    .message(AssistantMessage.builder()
                            .name(response.content.getFirst().name)
                            .content(response.content.getFirst().text)
                            .build())
                    .finishReason(response.stopReason)
                    .build()));
            assertThat(actual.usage()).isEqualTo(Usage.builder()
                    .promptTokens(response.usage.inputTokens)
                    .completionTokens(response.usage.outputTokens)
                    .totalTokens(response.usage.inputTokens + response.usage.outputTokens)
                    .build());
        }

        @Test
        void toCreateMessage() {
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);

            AnthropicCreateMessageRequest actual = LlmProviderAnthropicMapper.INSTANCE
                    .toCreateMessageRequest(request);

            assertThat(actual).isNotNull();
            assertThat(actual.model).isEqualTo(request.model());
            assertThat(actual.stream).isEqualTo(request.stream());
            assertThat(actual.temperature).isEqualTo(request.temperature());
            if (request.temperature() != null) {
                assertThat(actual.topP).isNull();
            } else {
                assertThat(actual.topP).isEqualTo(request.topP());
            }
            assertThat(actual.stopSequences).isEqualTo(request.stop());
            assertThat(actual.messages).usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(
                    request.messages().stream()
                            .filter(message -> List.of(Role.USER, Role.ASSISTANT).contains(message.role()))
                            .map(LlmProviderAnthropicMapper.INSTANCE::mapToAnthropicMessage)
                            .toList());
            assertThat(actual.system).usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(
                    request.messages().stream()
                            .filter(message -> message.role() == Role.SYSTEM)
                            .map(LlmProviderAnthropicMapper.INSTANCE::mapToSystemMessage)
                            .toList());
        }

        @Test
        void toCreateMessage_appliesDefaultMaxTokens_whenNull() {
            var request = ChatCompletionRequest.builder()
                    .model("claude-sonnet-4-6")
                    .stream(false)
                    .addUserMessage("hi")
                    .build();

            AnthropicCreateMessageRequest actual = LlmProviderAnthropicMapper.INSTANCE
                    .toCreateMessageRequest(request);

            assertThat(actual.maxTokens).isEqualTo(LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS);
        }

        @Test
        void toCreateMessage_preservesExplicitMaxTokens() {
            var request = ChatCompletionRequest.builder()
                    .model("claude-sonnet-4-6")
                    .stream(false)
                    .addUserMessage("hi")
                    .maxCompletionTokens(123)
                    .build();

            AnthropicCreateMessageRequest actual = LlmProviderAnthropicMapper.INSTANCE
                    .toCreateMessageRequest(request);

            assertThat(actual.maxTokens).isEqualTo(123);
        }

        @ParameterizedTest
        @MethodSource("streamValues")
        void toCreateMessage_mapsStreamWithNullDefaultingToFalse(Boolean input, boolean expected) {
            var request = ChatCompletionRequest.builder()
                    .model("claude-sonnet-4-6")
                    .stream(input)
                    .addUserMessage("hi")
                    .build();

            AnthropicCreateMessageRequest actual = LlmProviderAnthropicMapper.INSTANCE
                    .toCreateMessageRequest(request);

            assertThat(actual.stream).isEqualTo(expected);
        }

        static Stream<Arguments> streamValues() {
            return Stream.of(
                    Arguments.of(Boolean.TRUE, true),
                    Arguments.of(Boolean.FALSE, false),
                    Arguments.of(null, false));
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Sampling params gating")
    class SamplingParamsGating {

        @ParameterizedTest
        @ValueSource(strings = {"claude-sonnet-5", "claude-opus-4-7", "claude-opus-4-8"})
        void dropsTemperatureAndTopPForAdaptiveThinkingModels(String model) {
            var request = ChatCompletionRequest.builder()
                    .model(model)
                    .addUserMessage("hi")
                    .temperature(0.7)
                    .topP(0.9)
                    .build();

            var actual = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);

            assertThat(actual.temperature).isNull();
            assertThat(actual.topP).isNull();
        }

        @Test
        void forwardsTemperatureForModelsThatSupportSamplingParams() {
            var request = ChatCompletionRequest.builder()
                    .model("claude-3-7-sonnet-20250219")
                    .addUserMessage("hi")
                    .temperature(0.7)
                    .build();

            var actual = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);

            assertThat(actual.temperature).isEqualTo(0.7);
        }

        @Test
        void forwardsTopPWhenTemperatureAbsentOnSamplingCapableModel() {
            var request = ChatCompletionRequest.builder()
                    .model("claude-3-7-sonnet-20250219")
                    .addUserMessage("hi")
                    .topP(0.9)
                    .build();

            var actual = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);

            assertThat(actual.temperature).isNull();
            assertThat(actual.topP).isEqualTo(0.9);
        }

        @Test
        void temperatureWinsOverTopPWhenBothSetOnSamplingCapableModel() {
            var request = ChatCompletionRequest.builder()
                    .model("claude-3-7-sonnet-20250219")
                    .addUserMessage("hi")
                    .temperature(0.7)
                    .topP(0.9)
                    .build();

            var actual = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);

            assertThat(actual.temperature).isEqualTo(0.7);
            assertThat(actual.topP).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"enabled", "adaptive", "some-future-mode"})
        void dropsSamplingParamsWhenThinkingEnabledOnSamplingCapableModel(String thinkingType) {
            var request = ChatCompletionRequest.builder()
                    .model("claude-3-7-sonnet-20250219")
                    .addUserMessage("hi")
                    .temperature(0.7)
                    .topP(0.9)
                    .customParameters(Map.of("thinking", Map.of("type", thinkingType, "budget_tokens", 1024)))
                    .build();

            var actual = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);

            assertThat(actual.temperature).isNull();
            assertThat(actual.topP).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"disabled", "", " "})
        void forwardsTemperatureWhenThinkingDisabledOrBlankOnSamplingCapableModel(String thinkingType) {
            var request = ChatCompletionRequest.builder()
                    .model("claude-3-7-sonnet-20250219")
                    .addUserMessage("hi")
                    .temperature(0.7)
                    .customParameters(Map.of("thinking", Map.of("type", thinkingType)))
                    .build();

            var actual = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);

            assertThat(actual.temperature).isEqualTo(0.7);
        }

        @Test
        void forwardsTemperatureWhenThinkingBlockAbsentOnSamplingCapableModel() {
            var request = ChatCompletionRequest.builder()
                    .model("claude-3-7-sonnet-20250219")
                    .addUserMessage("hi")
                    .temperature(0.7)
                    .customParameters(Map.of("max_tokens", 2048))
                    .build();

            var actual = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);

            assertThat(actual.temperature).isEqualTo(0.7);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ValidateRequest {
        private final LlmProviderAnthropic provider = new LlmProviderAnthropic(Mockito.mock(AnthropicClient.class));

        @Test
        void acceptsNullMaxCompletionTokens() {
            var request = ChatCompletionRequest.builder()
                    .model("claude-sonnet-4-6")
                    .stream(false)
                    .addUserMessage("hi")
                    .build();

            assertThatCode(() -> provider.validateRequest(request)).doesNotThrowAnyException();
        }
    }
}
