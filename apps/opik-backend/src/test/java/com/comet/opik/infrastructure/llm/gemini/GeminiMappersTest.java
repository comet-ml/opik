package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.podam.PodamFactoryUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.shared.Usage;
import dev.langchain4j.model.output.FinishReason;
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

public class GeminiMappersTest {
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GeminiMappers {
        @Test
        void testToResponse() {
            var request = ChatCompletionRequest.builder().model(podamFactory.manufacturePojo(String.class)).build();
            var response = ChatResponse.builder()
                    .aiMessage(aiMessage(podamFactory.manufacturePojo(String.class)))
                    .tokenUsage(new TokenUsage(podamFactory.manufacturePojo(Integer.class),
                            podamFactory.manufacturePojo(Integer.class)))
                    .finishReason(FinishReason.STOP)
                    .build();
            var actual = LlmProviderLangChainMapper.INSTANCE.toChatCompletionResponse(request, response);
            assertThat(actual).isNotNull();
            assertThat(actual.model()).isEqualTo(request.model());
            assertThat(actual.choices()).isEqualTo(List.of(ChatCompletionChoice.builder()
                    .message(AssistantMessage.builder().content(response.aiMessage().text()).build())
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
            ChatMessage actual = LlmProviderLangChainMapper.INSTANCE.toChatMessage(message);
            assertThat(actual).isEqualTo(expected);
        }

        private Stream<Arguments> testToChatMessage() {
            var content = podamFactory.manufacturePojo(String.class);
            return Stream.of(
                    arguments(AssistantMessage.builder().content(content).build(), AiMessage.from(content)),
                    arguments(dev.langchain4j.model.openai.internal.chat.UserMessage.builder().content(content).build(),
                            UserMessage.from(content)),
                    arguments(
                            dev.langchain4j.model.openai.internal.chat.SystemMessage.builder().content(content).build(),
                            SystemMessage.from(content)));
        }
    }
}
