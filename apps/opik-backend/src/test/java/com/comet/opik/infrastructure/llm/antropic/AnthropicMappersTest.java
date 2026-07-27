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
import org.mockito.Mockito;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.HashMap;
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

        @ParameterizedTest(name = "{0}")
        @MethodSource("samplingGatingCases")
        void gatesSamplingParams(String description, ChatCompletionRequest request, Double expectedTemperature,
                Double expectedTopP) {
            var actual = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);

            // The gate only affects temperature/top_p; assert the full payload so a collateral change to any
            // other mapped field is caught too. The expected payload is the mapping of the same request with the
            // sampling params forced to what the gate should produce.
            var expected = LlmProviderAnthropicMapper.INSTANCE.toCreateMessageRequest(request);
            expected.setTemperature(expectedTemperature);
            expected.setTopP(expectedTopP);

            assertThat(actual.getTemperature()).isEqualTo(expectedTemperature);
            assertThat(actual.getTopP()).isEqualTo(expectedTopP);
            assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
        }

        Stream<Arguments> samplingGatingCases() {
            return Stream.of(
                    // Adaptive-thinking models drop both sampling params regardless of what was requested.
                    adaptiveDropsBoth("claude-sonnet-5"),
                    adaptiveDropsBoth("claude-opus-4-7"),
                    adaptiveDropsBoth("claude-opus-4-8"),
                    // Sampling-capable model: params forwarded, with temperature winning over top_p.
                    Arguments.of("sampling-capable forwards temperature",
                            samplingCapable().temperature(0.7).build(), 0.7, null),
                    Arguments.of("sampling-capable forwards top_p when temperature absent",
                            samplingCapable().topP(0.9).build(), null, 0.9),
                    Arguments.of("sampling-capable drops top_p when temperature also set",
                            samplingCapable().temperature(0.7).topP(0.9).build(), 0.7, null),
                    // Thinking enabled (any non-blank type other than "disabled", case-insensitive) drops both.
                    thinkingDropsBoth("enabled"),
                    thinkingDropsBoth("ENABLED"),
                    thinkingDropsBoth("adaptive"),
                    thinkingDropsBoth("some-future-mode"),
                    // Thinking disabled / blank / null / absent leaves sampling params untouched.
                    thinkingForwards("thinking disabled forwards", Map.of("thinking", Map.of("type", "disabled"))),
                    thinkingForwards("thinking DISABLED (case-insensitive) forwards",
                            Map.of("thinking", Map.of("type", "DISABLED"))),
                    thinkingForwards("thinking blank type forwards", Map.of("thinking", Map.of("type", " "))),
                    thinkingForwards("thinking null type forwards", nullThinkingTypeParameters()),
                    thinkingForwards("thinking block absent forwards", Map.of("max_tokens", 2048)));
        }

        private Arguments adaptiveDropsBoth(String model) {
            return Arguments.of("adaptive model %s drops both".formatted(model),
                    ChatCompletionRequest.builder().model(model).addUserMessage("hi")
                            .temperature(0.7).topP(0.9).build(),
                    null, null);
        }

        private Arguments thinkingDropsBoth(String thinkingType) {
            return Arguments.of("thinking type '%s' drops both".formatted(thinkingType),
                    samplingCapable().temperature(0.7).topP(0.9)
                            .customParameters(Map.of("thinking", Map.of("type", thinkingType, "budget_tokens", 1024)))
                            .build(),
                    null, null);
        }

        private Arguments thinkingForwards(String description, Map<String, Object> customParameters) {
            return Arguments.of(description,
                    samplingCapable().temperature(0.7).customParameters(customParameters).build(), 0.7, null);
        }

        private ChatCompletionRequest.Builder samplingCapable() {
            return ChatCompletionRequest.builder().model("claude-3-7-sonnet-20250219").addUserMessage("hi");
        }

        // Map.of rejects null values, so build the explicit null-type thinking block with a nested HashMap.
        private Map<String, Object> nullThinkingTypeParameters() {
            var thinking = new HashMap<String, Object>();
            thinking.put("type", null);
            return Map.of("thinking", thinking);
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
