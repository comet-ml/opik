package com.comet.opik.domain.llmproviders;

import com.comet.opik.podam.PodamFactoryUtils;
import dev.ai4j.openai4j.chat.AssistantMessage;
import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.shared.Usage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
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
        // anthropic POJOs don't have setters or builders, therefore podam can't manufacture objects correctly

        @Test
        void testToResponse() {
            var content = new AnthropicContent();
            content.name = podamFactory.manufacturePojo(String.class);
            content.text = podamFactory.manufacturePojo(String.class);
            content.id = podamFactory.manufacturePojo(String.class);

            var usage = new AnthropicUsage();
            usage.inputTokens = podamFactory.manufacturePojo(Integer.class);
            usage.outputTokens = podamFactory.manufacturePojo(Integer.class);

            var response = new AnthropicCreateMessageResponse();
            response.id = podamFactory.manufacturePojo(String.class);
            response.model = podamFactory.manufacturePojo(String.class);
            response.stopReason = podamFactory.manufacturePojo(String.class);
            response.content = List.of(content);
            response.usage = usage;

            var actual = AnthropicToChatCompletionsMapper.INSTANCE.toResponse(response);
            assertThat(actual).isNotNull();
            assertThat(actual.id()).isEqualTo(response.id);
            assertThat(actual.choices()).isEqualTo(List.of(ChatCompletionChoice.builder()
                    .message(AssistantMessage.builder()
                            .name(content.name)
                            .content(content.text)
                            .build())
                    .finishReason(response.stopReason)
                    .build()));
            assertThat(actual.usage()).isEqualTo(Usage.builder()
                    .promptTokens(usage.inputTokens)
                    .completionTokens(usage.outputTokens)
                    .totalTokens(usage.inputTokens + usage.outputTokens)
                    .build());
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
