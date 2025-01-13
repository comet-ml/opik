package com.comet.opik.domain.llmproviders;

import com.comet.opik.podam.PodamFactoryUtils;
import dev.ai4j.openai4j.chat.AssistantMessage;
import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.chat.Role;
import dev.ai4j.openai4j.shared.Usage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.stream.Stream;

import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class LlmProviderClientsMappersTest {
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
            assertThat(actual.topP).isEqualTo(request.topP());
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
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GeminiMappers {
        @Test
        void testToResponse() {
            var request = ChatCompletionRequest.builder().model(podamFactory.manufacturePojo(String.class)).build();
            var response = new Response<>(aiMessage(podamFactory.manufacturePojo(String.class)),
                    new TokenUsage(podamFactory.manufacturePojo(Integer.class),
                            podamFactory.manufacturePojo(Integer.class)),
                    FinishReason.STOP);
            var actual = LlmProviderGeminiMapper.INSTANCE.toChatCompletionResponse(request, response);
            assertThat(actual).isNotNull();
            assertThat(actual.model()).isEqualTo(request.model());
            assertThat(actual.choices()).isEqualTo(List.of(ChatCompletionChoice.builder()
                    .message(AssistantMessage.builder().content(response.content().text()).build())
                    .build()));
            assertThat(actual.usage()).isEqualTo(Usage.builder()
                    .promptTokens(response.tokenUsage().inputTokenCount())
                    .completionTokens(response.tokenUsage().outputTokenCount())
                    .totalTokens(response.tokenUsage().totalTokenCount())
                    .build());
        }

        @ParameterizedTest
        @MethodSource
        void testToChatMessage(Message message, ChatMessage expected) {
            ChatMessage actual = LlmProviderGeminiMapper.INSTANCE.toChatMessage(message);
            assertThat(actual).isEqualTo(expected);
        }

        private Stream<Arguments> testToChatMessage() {
            var content = podamFactory.manufacturePojo(String.class);
            return Stream.of(
                    arguments(AssistantMessage.builder().content(content).build(), AiMessage.from(content)),
                    arguments(dev.ai4j.openai4j.chat.UserMessage.builder().content(content).build(),
                            UserMessage.from(content)),
                    arguments(dev.ai4j.openai4j.chat.SystemMessage.builder().content(content).build(),
                            SystemMessage.from(content)));
        }
    }
}
