package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProviderOpenAiResponsesMapperTest {

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ToChatRequest {

        @Test
        void mapsModelAndBasicSamplingParameters() {
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .temperature(0.7)
                    .topP(0.9)
                    .frequencyPenalty(0.1)
                    .presencePenalty(0.2)
                    .maxCompletionTokens(512)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.modelName()).isEqualTo("gpt-4o-mini");
            assertThat(actual.temperature()).isEqualTo(0.7);
            assertThat(actual.topP()).isEqualTo(0.9);
            assertThat(actual.frequencyPenalty()).isEqualTo(0.1);
            assertThat(actual.presencePenalty()).isEqualTo(0.2);
            assertThat(actual.maxOutputTokens()).isEqualTo(512);
        }

        @Test
        void mapsSystemUserAndAssistantMessages() {
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addSystemMessage("you are helpful")
                    .addUserMessage("question?")
                    .addAssistantMessage("answer.")
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.messages()).hasSize(3);
            assertThat(actual.messages().get(0)).isInstanceOf(SystemMessage.class);
            assertThat(((SystemMessage) actual.messages().get(0)).text()).isEqualTo("you are helpful");
            assertThat(actual.messages().get(1)).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) actual.messages().get(1)).singleText()).isEqualTo("question?");
            assertThat(actual.messages().get(2)).isInstanceOf(AiMessage.class);
            assertThat(((AiMessage) actual.messages().get(2)).text()).isEqualTo("answer.");
        }

        @Test
        void mapsStopSequences() {
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .stop(List.of("STOP1", "STOP2"))
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.stopSequences()).containsExactly("STOP1", "STOP2");
        }

        @Test
        void prefersMaxCompletionTokensOverMaxTokens() {
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .maxTokens(100)
                    .maxCompletionTokens(200)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.maxOutputTokens()).isEqualTo(200);
        }

        @Test
        void fallsBackToMaxTokensWhenMaxCompletionTokensAbsent() {
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .maxTokens(150)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.maxOutputTokens()).isEqualTo(150);
        }

    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ToChatCompletionResponse {

        @Test
        void mapsHappyPathWithMetadataAndUsage() {
            var originalRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("hello back"))
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_abc")
                            .modelName("gpt-4o-mini-2024-07-18")
                            .tokenUsage(new TokenUsage(7, 5, 12))
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatCompletionResponse(chatResponse, originalRequest);

            assertThat(actual.id()).isEqualTo("resp_abc");
            assertThat(actual.model()).isEqualTo("gpt-4o-mini-2024-07-18");
            assertThat(actual.choices()).hasSize(1);
            assertThat(actual.choices().getFirst().index()).isZero();
            assertThat(actual.choices().getFirst().message().content()).isEqualTo("hello back");
            assertThat(actual.choices().getFirst().finishReason()).isEqualTo("stop");
            assertThat(actual.usage().promptTokens()).isEqualTo(7);
            assertThat(actual.usage().completionTokens()).isEqualTo(5);
            assertThat(actual.usage().totalTokens()).isEqualTo(12);
        }

        @Test
        void fallsBackToRequestModelWhenMetadataModelMissing() {
            var originalRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok"))
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_x")
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatCompletionResponse(chatResponse, originalRequest);

            assertThat(actual.model()).isEqualTo("gpt-4o-mini");
        }

        @Test
        void handlesNullUsage() {
            var originalRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok"))
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_x")
                            .modelName("gpt-4o-mini")
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatCompletionResponse(chatResponse, originalRequest);

            assertThat(actual.usage()).isNull();
        }

        @ParameterizedTest
        @MethodSource("finishReasonMappings")
        void mapsFinishReasonEnumToOpenAiString(FinishReason input, String expected) {
            var originalRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok"))
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_x")
                            .modelName("gpt-4o-mini")
                            .finishReason(input)
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatCompletionResponse(chatResponse, originalRequest);

            assertThat(actual.choices().getFirst().finishReason()).isEqualTo(expected);
        }

        static Stream<Arguments> finishReasonMappings() {
            return Stream.of(
                    Arguments.of(FinishReason.STOP, "stop"),
                    Arguments.of(FinishReason.LENGTH, "length"),
                    Arguments.of(FinishReason.TOOL_EXECUTION, "tool_calls"),
                    Arguments.of(FinishReason.CONTENT_FILTER, "content_filter"),
                    Arguments.of(FinishReason.OTHER, "other"));
        }

    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StreamingChunks {

        @Test
        void partialChunkCarriesAssistantRoleAndContent() {
            var originalRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .build();

            var chunk = LlmProviderOpenAiResponsesMapper.toPartialChunk("hello", originalRequest);

            assertThat(chunk.choices()).hasSize(1);
            var choice = chunk.choices().getFirst();
            assertThat(choice.index()).isZero();
            assertThat(choice.delta().role()).isEqualTo("assistant");
            assertThat(choice.delta().content()).isEqualTo("hello");
            assertThat(choice.finishReason()).isNull();
            assertThat(chunk.model()).isEqualTo("gpt-4o-mini");
        }

        @Test
        void finalChunkSetsFinishReasonWithEmptyDelta() {
            var originalRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_x")
                            .modelName("gpt-4o-mini-2024-07-18")
                            .tokenUsage(new TokenUsage(7, 5, 12))
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();

            var chunk = LlmProviderOpenAiResponsesMapper.toFinalChunk(chatResponse, originalRequest);

            var choice = chunk.choices().getFirst();
            assertThat(choice.finishReason()).isEqualTo("stop");
            assertThat(choice.delta().content()).isNull();
            assertThat(choice.delta().role()).isNull();
            assertThat(chunk.usage().promptTokens()).isEqualTo(7);
            assertThat(chunk.usage().completionTokens()).isEqualTo(5);
            assertThat(chunk.usage().totalTokens()).isEqualTo(12);
            assertThat(chunk.id()).isEqualTo("resp_x");
            assertThat(chunk.model()).isEqualTo("gpt-4o-mini-2024-07-18");
        }

        @Test
        void finalChunkFallsBackToRequestModelWhenMetadataModelMissing() {
            var originalRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_x")
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();

            var chunk = LlmProviderOpenAiResponsesMapper.toFinalChunk(chatResponse, originalRequest);

            assertThat(chunk.model()).isEqualTo("gpt-4o-mini");
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UnsupportedRoles {

        @Test
        void rejectsToolMessages() {
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .addToolMessage("call_1", "{\"result\": 42}")
                    .build();

            assertThatThrownBy(() -> LlmProviderOpenAiResponsesMapper.toChatRequest(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Unsupported message role");
        }
    }
}