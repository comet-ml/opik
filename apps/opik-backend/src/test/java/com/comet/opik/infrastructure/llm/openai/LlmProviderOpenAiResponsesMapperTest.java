package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
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
import jakarta.ws.rs.BadRequestException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProviderOpenAiResponsesMapperTest {

    private static final String MODEL_NAME = "gpt-4o-mini";
    private static final String DEFAULT_USER_MESSAGE = "hi";

    private static ChatCompletionRequest.Builder requestBuilder() {
        return ChatCompletionRequest.builder().model(MODEL_NAME);
    }

    private static ChatCompletionRequest.Builder requestBuilder(String userMessage) {
        return requestBuilder().addUserMessage(userMessage);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ToChatRequest {

        @Test
        void mapsModelAndBasicSamplingParameters() {
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .temperature(0.7)
                    .topP(0.9)
                    .maxCompletionTokens(512)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.modelName()).isEqualTo(request.model());
            assertThat(actual.temperature()).isEqualTo(request.temperature());
            assertThat(actual.topP()).isEqualTo(request.topP());
            assertThat(actual.maxOutputTokens()).isEqualTo(request.maxCompletionTokens());
        }

        @Test
        void dropsSamplingParametersUnsupportedByResponsesApi() {
            // OpenAI Responses API rejects frequency_penalty, presence_penalty, and stop sequences.
            // langchain4j's OpenAiOfficialResponsesChatModel.validate() throws on any of them.
            // The mapper silently drops these (typically framework defaults like
            // frequency_penalty: 0 from playground SDKs) to keep the request from failing.
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .frequencyPenalty(0.5)
                    .presencePenalty(0.5)
                    .stop(List.of("STOP1", "STOP2"))
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.frequencyPenalty()).isNull();
            assertThat(actual.presencePenalty()).isNull();
            assertThat(actual.stopSequences()).isNullOrEmpty();
        }

        @Test
        void mapsSystemUserAndAssistantMessages() {
            var request = requestBuilder()
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
        void handlesOpikUserMessageInPlaceOfOpenAiUserMessage() {
            // MessageContentNormalizer rewrites multimodal user turns into OpikUserMessage (a
            // sibling of openai-internal UserMessage that implements Message directly). The mapper
            // must translate it through the same flattening path, not fall to the default branch.
            var opikUser = com.comet.opik.domain.llm.langchain4j.OpikUserMessage.builder()
                    .content("classify this")
                    .build();
            var request = requestBuilder()
                    .messages(List.of(opikUser))
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.messages()).hasSize(1);
            var translated = (UserMessage) actual.messages().getFirst();
            assertThat(translated.singleText()).isEqualTo("classify this");
        }

        @Test
        void prefersMaxCompletionTokensOverMaxTokens() {
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .maxTokens(100)
                    .maxCompletionTokens(200)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.maxOutputTokens()).isEqualTo(request.maxCompletionTokens());
        }

        @Test
        void fallsBackToMaxTokensWhenMaxCompletionTokensAbsent() {
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .maxTokens(150)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.maxOutputTokens()).isEqualTo(request.maxTokens());
        }

    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ToChatCompletionResponse {

        @Test
        void mapsHappyPathWithMetadataAndUsage() {
            var originalRequest = requestBuilder(DEFAULT_USER_MESSAGE).build();
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

            var metadata = chatResponse.metadata();
            var tokenUsage = metadata.tokenUsage();
            assertThat(actual.id()).isEqualTo(metadata.id());
            assertThat(actual.model()).isEqualTo(metadata.modelName());
            assertThat(actual.choices()).hasSize(1);
            assertThat(actual.choices().getFirst().index()).isZero();
            assertThat(actual.choices().getFirst().message().content())
                    .isEqualTo(chatResponse.aiMessage().text());
            assertThat(actual.choices().getFirst().finishReason()).isEqualTo("stop");
            assertThat(actual.usage().promptTokens()).isEqualTo(tokenUsage.inputTokenCount());
            assertThat(actual.usage().completionTokens()).isEqualTo(tokenUsage.outputTokenCount());
            assertThat(actual.usage().totalTokens()).isEqualTo(tokenUsage.totalTokenCount());
        }

        @Test
        void fallsBackToRequestModelWhenMetadataModelMissing() {
            var originalRequest = requestBuilder(DEFAULT_USER_MESSAGE).build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("ok"))
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_x")
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatCompletionResponse(chatResponse, originalRequest);

            assertThat(actual.model()).isEqualTo(originalRequest.model());
        }

        @Test
        void handlesNullUsage() {
            var originalRequest = requestBuilder(DEFAULT_USER_MESSAGE).build();
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
            var originalRequest = requestBuilder(DEFAULT_USER_MESSAGE).build();
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
            var originalRequest = requestBuilder(DEFAULT_USER_MESSAGE).build();
            var partial = "hello";

            var chunk = LlmProviderOpenAiResponsesMapper.toPartialChunk(partial, originalRequest);

            assertThat(chunk.choices()).hasSize(1);
            var choice = chunk.choices().getFirst();
            assertThat(choice.index()).isZero();
            assertThat(choice.delta().role()).isEqualTo("assistant");
            assertThat(choice.delta().content()).isEqualTo(partial);
            assertThat(choice.finishReason()).isNull();
            assertThat(chunk.model()).isEqualTo(originalRequest.model());
        }

        @Test
        void finalChunkSetsFinishReasonWithEmptyDelta() {
            var originalRequest = requestBuilder(DEFAULT_USER_MESSAGE).build();
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

            var metadata = chatResponse.metadata();
            var tokenUsage = metadata.tokenUsage();
            var choice = chunk.choices().getFirst();
            assertThat(choice.finishReason()).isEqualTo("stop");
            assertThat(choice.delta().content()).isNull();
            assertThat(choice.delta().role()).isNull();
            assertThat(chunk.usage().promptTokens()).isEqualTo(tokenUsage.inputTokenCount());
            assertThat(chunk.usage().completionTokens()).isEqualTo(tokenUsage.outputTokenCount());
            assertThat(chunk.usage().totalTokens()).isEqualTo(tokenUsage.totalTokenCount());
            assertThat(chunk.id()).isEqualTo(metadata.id());
            assertThat(chunk.model()).isEqualTo(metadata.modelName());
        }

        @Test
        void finalChunkFallsBackToRequestModelWhenMetadataModelMissing() {
            var originalRequest = requestBuilder(DEFAULT_USER_MESSAGE).build();
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from("done"))
                    .metadata(ChatResponseMetadata.builder()
                            .id("resp_x")
                            .finishReason(FinishReason.STOP)
                            .build())
                    .build();

            var chunk = LlmProviderOpenAiResponsesMapper.toFinalChunk(chatResponse, originalRequest);

            assertThat(chunk.model()).isEqualTo(originalRequest.model());
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
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .tools(Tool.from(weatherFn))
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.toolSpecifications()).hasSize(1);
            var spec = actual.toolSpecifications().getFirst();
            assertThat(spec.name()).isEqualTo(weatherFn.name());
            assertThat(spec.description()).isEqualTo(weatherFn.description());
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
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .toolChoice((Object) openAiValue)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.toolChoice()).isEqualTo(expected);
        }

        @Test
        void rejectsNamedFunctionToolChoiceInsteadOfSilentlyDegrading() {
            // Real wire shape: tool_choice = {"type":"function","function":{"name":"get_weather"}}.
            // We reject because forcing a specific function has no langchain4j ToolChoice equivalent,
            // and silently mapping to AUTO would lose the caller's intent.
            var namedFunctionChoice = Map.of(
                    "type", "function",
                    "function", Map.of("name", "get_weather"));
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .toolChoice(namedFunctionChoice)
                    .build();

            assertThatThrownBy(() -> LlmProviderOpenAiResponsesMapper.toChatRequest(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Named-function tool_choice is not supported");
        }

        @Test
        void translatesToolResultMessageForResume() {
            var toolCallId = "call_42";
            var toolResult = "{\"temp_c\": 21}";
            var request = requestBuilder("what's the weather?")
                    .addToolMessage(toolCallId, toolResult)
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.messages()).hasSize(2);
            var resume = (ToolExecutionResultMessage) actual.messages().get(1);
            assertThat(resume.id()).isEqualTo(toolCallId);
            assertThat(resume.text()).isEqualTo(toolResult);
        }

        @Test
        void translatesAssistantMessageWithToolCallsForResume() {
            // Client typically replays the prior assistant turn (with tool_calls) when resuming the loop.
            var toolCall = ToolCall.builder()
                    .id("call_42")
                    .type(ToolType.FUNCTION)
                    .function(FunctionCall.builder()
                            .name("get_weather")
                            .arguments("{\"city\":\"Lisbon\"}")
                            .build())
                    .build();
            var assistantWithCall = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
            var request = requestBuilder()
                    .messages(List.of(
                            dev.langchain4j.model.openai.internal.chat.UserMessage.from(DEFAULT_USER_MESSAGE),
                            assistantWithCall,
                            dev.langchain4j.model.openai.internal.chat.ToolMessage.builder()
                                    .toolCallId(toolCall.id())
                                    .content("ok")
                                    .build()))
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            var assistant = (AiMessage) actual.messages().get(1);
            assertThat(assistant.toolExecutionRequests()).hasSize(1);
            var req = assistant.toolExecutionRequests().getFirst();
            assertThat(req.id()).isEqualTo(toolCall.id());
            assertThat(req.name()).isEqualTo(toolCall.function().name());
            assertThat(req.arguments()).isEqualTo(toolCall.function().arguments());
        }

        // ─── response side ──────────────────────────────────────────────────────────

        @Test
        void emitsAssistantToolCallsWhenAiMessageReturnsToolExecutionRequests() {
            var originalRequest = requestBuilder("what's the weather?").build();
            var toolExecRequest = ToolExecutionRequest.builder()
                    .id("call_42")
                    .name("get_weather")
                    .arguments("{\"city\":\"Lisbon\"}")
                    .build();
            var aiMessage = AiMessage.builder()
                    .toolExecutionRequests(List.of(toolExecRequest))
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
            assertThat(call.id()).isEqualTo(toolExecRequest.id());
            assertThat(call.type()).isEqualTo(ToolType.FUNCTION);
            assertThat(call.function().name()).isEqualTo(toolExecRequest.name());
            assertThat(call.function().arguments()).isEqualTo(toolExecRequest.arguments());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class StructuredOutput {

        @Test
        void mapsJsonObjectResponseFormatToLooseJsonMode() {
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .responseFormat(dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                            .type(dev.langchain4j.model.openai.internal.chat.ResponseFormatType.JSON_OBJECT)
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.responseFormat())
                    .isEqualTo(dev.langchain4j.model.chat.request.ResponseFormat.JSON);
        }

        @Test
        void mapsJsonSchemaResponseFormatWithNameAndRootObjectSchema() {
            var schemaName = "classification";
            var schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "summary", Map.of("type", "string"),
                            "sentiment", Map.of("type", "string", "enum", List.of("positive", "negative"))),
                    "required", List.of("summary", "sentiment"));
            var request = requestBuilder("classify")
                    .responseFormat(dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                            .type(dev.langchain4j.model.openai.internal.chat.ResponseFormatType.JSON_SCHEMA)
                            .jsonSchema(dev.langchain4j.model.openai.internal.chat.JsonSchema.builder()
                                    .name(schemaName)
                                    .strict(true)
                                    .schema(schema)
                                    .build())
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
            var translatedJsonSchema = actual.responseFormat().jsonSchema();
            assertThat(translatedJsonSchema.name()).isEqualTo(schemaName);
            var root = (JsonObjectSchema) translatedJsonSchema.rootElement();
            assertThat(root.properties()).containsKeys("summary", "sentiment");
            assertThat(root.required()).containsExactlyInAnyOrder("summary", "sentiment");
        }

        @Test
        void leavesResponseFormatUnsetWhenRequestOmitsIt() {
            var request = requestBuilder(DEFAULT_USER_MESSAGE).build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.responseFormat()).isNull();
        }

        @Test
        void textResponseFormatLeavesChatRequestDefault() {
            // text is OpenAI's default — explicitly setting it should not force a non-default
            // ResponseFormat on the langchain4j ChatRequest.
            var request = requestBuilder(DEFAULT_USER_MESSAGE)
                    .responseFormat(dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                            .type(dev.langchain4j.model.openai.internal.chat.ResponseFormatType.TEXT)
                            .build())
                    .build();

            var actual = LlmProviderOpenAiResponsesMapper.toChatRequest(request);

            assertThat(actual.responseFormat()).isNull();
        }

        @Test
        void extractRequestedStrictJsonSchemaReturnsTrueOnlyForExplicitStrictTrue() {
            var strictTrue = requestBuilder(DEFAULT_USER_MESSAGE)
                    .responseFormat(dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                            .type(dev.langchain4j.model.openai.internal.chat.ResponseFormatType.JSON_SCHEMA)
                            .jsonSchema(dev.langchain4j.model.openai.internal.chat.JsonSchema.builder()
                                    .name("s")
                                    .strict(true)
                                    .schema(Map.of("type", "object"))
                                    .build())
                            .build())
                    .build();
            var strictFalse = requestBuilder(DEFAULT_USER_MESSAGE)
                    .responseFormat(dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                            .type(dev.langchain4j.model.openai.internal.chat.ResponseFormatType.JSON_SCHEMA)
                            .jsonSchema(dev.langchain4j.model.openai.internal.chat.JsonSchema.builder()
                                    .name("s")
                                    .strict(false)
                                    .schema(Map.of("type", "object"))
                                    .build())
                            .build())
                    .build();
            var jsonObject = requestBuilder(DEFAULT_USER_MESSAGE)
                    .responseFormat(dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                            .type(dev.langchain4j.model.openai.internal.chat.ResponseFormatType.JSON_OBJECT)
                            .build())
                    .build();
            var noResponseFormat = requestBuilder(DEFAULT_USER_MESSAGE).build();

            assertThat(LlmProviderOpenAiResponsesMapper.extractRequestedStrictJsonSchema(strictTrue)).isTrue();
            assertThat(LlmProviderOpenAiResponsesMapper.extractRequestedStrictJsonSchema(strictFalse)).isFalse();
            assertThat(LlmProviderOpenAiResponsesMapper.extractRequestedStrictJsonSchema(jsonObject)).isFalse();
            assertThat(LlmProviderOpenAiResponsesMapper.extractRequestedStrictJsonSchema(noResponseFormat)).isFalse();
        }
    }
}
