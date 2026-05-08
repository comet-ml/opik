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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;

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
