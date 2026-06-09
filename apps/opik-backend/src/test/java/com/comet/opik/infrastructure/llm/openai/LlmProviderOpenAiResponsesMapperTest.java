package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Function;
import dev.langchain4j.model.openai.internal.chat.FunctionCall;
import dev.langchain4j.model.openai.internal.chat.Tool;
import dev.langchain4j.model.openai.internal.chat.ToolCall;
import dev.langchain4j.model.openai.internal.chat.ToolType;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
    class ToolCalling {

        // ─── request side ───────────────────────────────────────────────────────────

        @Test
        void propagatesToolSpecificationsWithJsonSchemaParameters() {
            var weatherFn = Function.builder()
                    .name("get_weather")
                    .description("Look up current weather")
                    .parameters(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "city", Map.of("type", "string", "description", "City name"),
                                    "units", Map.of("type", "string")),
                            "required", List.of("city")))
                    .build();
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .tools(Tool.from(weatherFn))
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.toolSpecifications()).hasSize(1);
            var spec = actual.toolSpecifications().getFirst();
            assertThat(spec.name()).isEqualTo("get_weather");
            assertThat(spec.description()).isEqualTo("Look up current weather");
            assertThat(spec.parameters()).isNotNull();
            assertThat(spec.parameters().properties()).containsKeys("city", "units");
            assertThat(spec.parameters().required()).containsExactly("city");
        }

        @ParameterizedTest
        @CsvSource({"auto,AUTO", "required,REQUIRED", "none,NONE"})
        void mapsStringToolChoiceToEnum(String openAiValue, ToolChoice expected) {
            // ChatCompletionRequest.Builder.toolChoice(String) wraps the value into a named-function
            // ToolChoice — useful for the named-function variant but wrong for the plain string
            // forms. Real wire-format JSON deserializes the string into a raw String inside the
            // Object field, which is what the mapper handles. Force-cast to Object to mirror that.
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("hi")
                    .toolChoice((Object) openAiValue)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.toolChoice()).isEqualTo(expected);
        }

        @Test
        void translatesToolResultMessageForResume() {
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("what's the weather?")
                    .addToolMessage("call_42", "{\"temp_c\": 21}")
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.messages()).hasSize(2);
            var resume = (ToolExecutionResultMessage) actual.messages().get(1);
            assertThat(resume.id()).isEqualTo("call_42");
            assertThat(resume.text()).isEqualTo("{\"temp_c\": 21}");
        }

        @Test
        void translatesAssistantMessageWithToolCallsForResume() {
            // Client typically replays the prior assistant turn (with tool_calls) when resuming the loop.
            var assistantWithCall = AssistantMessage.builder()
                    .toolCalls(List.of(ToolCall.builder()
                            .id("call_42")
                            .type(ToolType.FUNCTION)
                            .function(FunctionCall.builder()
                                    .name("get_weather")
                                    .arguments("{\"city\":\"Lisbon\"}")
                                    .build())
                            .build()))
                    .build();
            var request = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .messages(List.of(
                            dev.langchain4j.model.openai.internal.chat.UserMessage.from("hi"),
                            assistantWithCall,
                            dev.langchain4j.model.openai.internal.chat.ToolMessage.builder()
                                    .toolCallId("call_42")
                                    .content("ok")
                                    .build()))
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            var assistant = (AiMessage) actual.messages().get(1);
            assertThat(assistant.toolExecutionRequests()).hasSize(1);
            var req = assistant.toolExecutionRequests().getFirst();
            assertThat(req.id()).isEqualTo("call_42");
            assertThat(req.name()).isEqualTo("get_weather");
            assertThat(req.arguments()).isEqualTo("{\"city\":\"Lisbon\"}");
        }

        // ─── response side ──────────────────────────────────────────────────────────

        @Test
        void emitsAssistantToolCallsWhenAiMessageReturnsToolExecutionRequests() {
            var originalRequest = ChatCompletionRequest.builder()
                    .model("gpt-4o-mini")
                    .addUserMessage("what's the weather?")
                    .build();
            var aiMessage = AiMessage.builder()
                    .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                            .id("call_42")
                            .name("get_weather")
                            .arguments("{\"city\":\"Lisbon\"}")
                            .build()))
                    .build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_x")
                            .modelName("gpt-4o-mini")
                            .finishReason(FinishReason.TOOL_EXECUTION)
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatCompletionResponse(chatResponse, originalRequest);

            var choice = actual.choices().getFirst();
            assertThat(choice.finishReason()).isEqualTo("tool_calls");
            assertThat(choice.message().toolCalls()).hasSize(1);
            var call = choice.message().toolCalls().getFirst();
            assertThat(call.id()).isEqualTo("call_42");
            assertThat(call.type()).isEqualTo(ToolType.FUNCTION);
            assertThat(call.function().name()).isEqualTo("get_weather");
            assertThat(call.function().arguments()).isEqualTo("{\"city\":\"Lisbon\"}");
        }
    }
}