package com.comet.opik.infrastructure.llm.freemodel;

import com.comet.opik.infrastructure.FreeModelConfig;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreeModelLlmProviderTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("generate should transform temperature correctly for reasoning models")
    void testGenerate_temperatureTransformation(
            String testName,
            Double inputTemperature,
            boolean isReasoningModel,
            Double expectedTemperature) {

        OpenAiClient mockClient = mock(OpenAiClient.class);
        Object mockCall = mock(Object.class);
        when(mockClient.chatCompletion(any())).thenAnswer(invocation -> {
            return mockCall;
        });

        String actualModel = "gpt-5-nano";
        FreeModelLlmProvider provider = new FreeModelLlmProvider(mockClient, actualModel, isReasoningModel);

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(FreeModelConfig.FREE_MODEL)
                .messages(List.of(UserMessage.builder()
                        .content("test message")
                        .build()))
                .temperature(inputTemperature)
                .build();

        try {
            provider.generate(request, "test-workspace-id");
        } catch (Exception e) {
        }

        ArgumentCaptor<ChatCompletionRequest> requestCaptor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(mockClient).chatCompletion(requestCaptor.capture());

        ChatCompletionRequest transformedRequest = requestCaptor.getValue();
        assertThat(transformedRequest.model()).isEqualTo(actualModel);
        assertThat(transformedRequest.temperature()).isEqualTo(expectedTemperature);
        assertThat(transformedRequest.messages()).isEqualTo(request.messages());
        assertThat(transformedRequest.reasoningEffort()).isEqualTo("none");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testGenerate_temperatureTransformation")
    @DisplayName("generateStream should transform temperature correctly for reasoning models")
    void testGenerateStream_temperatureTransformation(
            String testName,
            Double inputTemperature,
            boolean isReasoningModel,
            Double expectedTemperature) {

        OpenAiClient mockClient = mock(OpenAiClient.class);
        Object mockCall = mock(Object.class);
        when(mockClient.chatCompletion(any())).thenAnswer(invocation -> mockCall);

        String actualModel = "gpt-5-nano";
        FreeModelLlmProvider provider = new FreeModelLlmProvider(mockClient, actualModel, isReasoningModel);

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(FreeModelConfig.FREE_MODEL)
                .messages(List.of(UserMessage.builder()
                        .content("test message")
                        .build()))
                .temperature(inputTemperature)
                .build();

        try {
            provider.generateStream(
                    request,
                    "test-workspace-id",
                    response -> {
                    },
                    () -> {
                    },
                    error -> {
                    });
        } catch (Exception e) {
        }

        ArgumentCaptor<ChatCompletionRequest> requestCaptor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(mockClient).chatCompletion(requestCaptor.capture());

        ChatCompletionRequest transformedRequest = requestCaptor.getValue();
        assertThat(transformedRequest.model()).isEqualTo(actualModel);
        assertThat(transformedRequest.temperature()).isEqualTo(expectedTemperature);
        assertThat(transformedRequest.messages()).isEqualTo(request.messages());
        assertThat(transformedRequest.reasoningEffort()).isEqualTo("none");
    }

    private static Stream<Arguments> testGenerate_temperatureTransformation() {
        return Stream.of(
                arguments(
                        "Reasoning model with temperature < 1.0 should clamp to minimum",
                        0.0,
                        true,
                        FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE),
                arguments(
                        "Reasoning model with temperature = 0.5 should clamp to minimum",
                        0.5,
                        true,
                        FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE),
                arguments(
                        "Reasoning model with temperature = null should pass through",
                        null,
                        true,
                        null),
                arguments(
                        "Reasoning model with temperature = 1.0 should pass through",
                        1.0,
                        true,
                        1.0),
                arguments(
                        "Reasoning model with temperature > 1.0 should pass through",
                        1.5,
                        true,
                        1.5),
                arguments(
                        "Non-reasoning model with temperature < 1.0 should pass through",
                        0.0,
                        false,
                        0.0),
                arguments(
                        "Non-reasoning model with temperature = null should pass through",
                        null,
                        false,
                        null),
                arguments(
                        "Non-reasoning model with temperature = 1.0 should pass through",
                        1.0,
                        false,
                        1.0));
    }
}
