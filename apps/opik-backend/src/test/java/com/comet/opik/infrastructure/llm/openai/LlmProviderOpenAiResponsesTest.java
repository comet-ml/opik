package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Function;
import dev.langchain4j.model.openai.internal.chat.FunctionCall;
import dev.langchain4j.model.openai.internal.chat.JsonSchema;
import dev.langchain4j.model.openai.internal.chat.ResponseFormat;
import dev.langchain4j.model.openai.internal.chat.ResponseFormatType;
import dev.langchain4j.model.openai.internal.chat.Tool;
import dev.langchain4j.model.openai.internal.chat.ToolCall;
import dev.langchain4j.model.openai.internal.chat.ToolType;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;

/**
 * Integration-style coverage for {@link LlmProviderOpenAiResponses}: drives stub langchain4j
 * models to exercise both the blocking {@code generate} and streaming {@code generateStream} paths,
 * verifying that the real {@link LlmProviderOpenAiResponsesMapper} translates request/response and
 * streaming chunks correctly. Includes a full tool-calling round-trip (request with tools →
 * response with tool_calls → resume with tool result → final text).
 */
class LlmProviderOpenAiResponsesTest {

    @Test
    void streamingHappyPathProducesPartialChunksThenFinalChunkThenClose() {
        var partials = List.of("Hel", "lo", ", world!");
        var finalResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(String.join("", partials)))
                .metadata(ChatResponseMetadata.builder()
                        .id("resp_42")
                        .modelName("gpt-4o-mini-2024-07-18")
                        .tokenUsage(new TokenUsage(3, 4, 7))
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
        var streamingModel = streamingModelEmitting(partials, finalResponse);
        var provider = providerWithStubs(Mockito.mock(ChatModel.class), streamingModel);
        var request = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")
                .addUserMessage("hi")
                .build();
        var received = new ArrayList<ChatCompletionResponse>();
        var closes = new AtomicInteger();
        var errors = new ArrayList<Throwable>();

        provider.generateStream(request, "ws-1", received::add, closes::incrementAndGet, errors::add);

        // 3 partial chunks + 1 final chunk = 4 messages, then exactly one close, zero errors.
        assertThat(received).hasSize(4);
        assertThat(closes).hasValue(1);
        assertThat(errors).isEmpty();

        // Partials carry assistant role + content; finish_reason and usage are still null.
        for (int i = 0; i < partials.size(); i++) {
            var chunk = received.get(i);
            var choice = chunk.choices().getFirst();
            assertThat(choice.delta().role()).isEqualTo("assistant");
            assertThat(choice.delta().content()).isEqualTo(partials.get(i));
            assertThat(choice.finishReason()).isNull();
            assertThat(chunk.usage()).isNull();
        }

        // Final chunk: empty delta, finish_reason and usage populated, id/model preserved.
        var finalChunk = received.getLast();
        var finalChoice = finalChunk.choices().getFirst();
        assertThat(finalChoice.delta().role()).isNull();
        assertThat(finalChoice.delta().content()).isNull();
        assertThat(finalChoice.finishReason()).isEqualTo("stop");
        assertThat(finalChunk.id()).isEqualTo("resp_42");
        assertThat(finalChunk.model()).isEqualTo("gpt-4o-mini-2024-07-18");
        assertThat(finalChunk.usage().promptTokens()).isEqualTo(3);
        assertThat(finalChunk.usage().completionTokens()).isEqualTo(4);
        assertThat(finalChunk.usage().totalTokens()).isEqualTo(7);
    }

    @Test
    void streamingErrorRoutesToErrorHandlerWithoutCallingClose() {
        var failure = new RuntimeException("upstream blew up");
        var streamingModel = new StubStreamingChatModel((req, handler) -> handler.onError(failure));
        var provider = providerWithStubs(Mockito.mock(ChatModel.class), streamingModel);
        var request = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")
                .addUserMessage("hi")
                .build();
        var received = new ArrayList<ChatCompletionResponse>();
        var closes = new AtomicInteger();
        var errors = new ArrayList<Throwable>();

        provider.generateStream(request, "ws-1", received::add, closes::incrementAndGet, errors::add);

        assertThat(received).isEmpty();
        assertThat(closes).hasValue(0);
        assertThat(errors).containsExactly(failure);
    }

    @Test
    void toolLoopRoundTrip_clientReceivesToolCallsThenFinalAnswer() {
        // Stub ChatModel scripted by inspecting the incoming ChatRequest:
        //   turn 1 — messages contain only a UserMessage → return AiMessage with a tool call.
        //   turn 2 — messages also contain a ToolExecutionResultMessage → return final text.
        // Scripting by input shape (not call count) keeps the loop semantics explicit.
        var firstCallResponse = ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                .id("call_42")
                                .name("get_weather")
                                .arguments("{\"city\":\"Lisbon\"}")
                                .build()))
                        .build())
                .metadata(ChatResponseMetadata.builder()
                        .id("resp_tool")
                        .modelName("gpt-4o-mini")
                        .tokenUsage(new TokenUsage(20, 6, 26))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build())
                .build();
        var secondCallResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("It's 21°C and sunny in Lisbon."))
                .metadata(ChatResponseMetadata.builder()
                        .id("resp_final")
                        .modelName("gpt-4o-mini")
                        .tokenUsage(new TokenUsage(35, 11, 46))
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
        var capturedRequests = new ArrayList<ChatRequest>();
        var chatModel = new StubChatModel(chatRequest -> {
            capturedRequests.add(chatRequest);
            boolean hasToolResult = chatRequest.messages().stream()
                    .anyMatch(ToolExecutionResultMessage.class::isInstance);
            return hasToolResult ? secondCallResponse : firstCallResponse;
        });
        var provider = providerWithStubs(chatModel, Mockito.mock(StreamingChatModel.class));

        // ─── turn 1: user question + tool spec ──────────────────────────────────────
        var weatherTool = Tool.from(Function.builder()
                .name("get_weather")
                .description("Look up current weather")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string")),
                        "required", List.of("city")))
                .build());
        var firstRequest = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")
                .addUserMessage("What's the weather in Lisbon?")
                .tools(weatherTool)
                .build();

        var firstResponse = provider.generate(firstRequest, "ws-1");

        // Proxy forwarded the tool spec to langchain4j.
        assertThat(capturedRequests).hasSize(1);
        assertThat(capturedRequests.getFirst().toolSpecifications()).hasSize(1);
        assertThat(capturedRequests.getFirst().toolSpecifications().getFirst().name())
                .isEqualTo("get_weather");

        // Client sees the tool call shaped as OpenAI Chat-Completions tool_calls.
        var firstChoice = firstResponse.choices().getFirst();
        assertThat(firstChoice.finishReason()).isEqualTo("tool_calls");
        assertThat(firstChoice.message().toolCalls()).hasSize(1);
        var emittedToolCall = firstChoice.message().toolCalls().getFirst();
        assertThat(emittedToolCall.id()).isEqualTo("call_42");
        assertThat(emittedToolCall.function().name()).isEqualTo("get_weather");
        assertThat(emittedToolCall.function().arguments()).isEqualTo("{\"city\":\"Lisbon\"}");

        // ─── turn 2: client dispatched the tool locally and resumes the loop ────────
        var secondRequest = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(
                        dev.langchain4j.model.openai.internal.chat.UserMessage.from("What's the weather in Lisbon?"),
                        AssistantMessage.builder()
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call_42")
                                        .type(ToolType.FUNCTION)
                                        .function(FunctionCall.builder()
                                                .name("get_weather")
                                                .arguments("{\"city\":\"Lisbon\"}")
                                                .build())
                                        .build()))
                                .build(),
                        dev.langchain4j.model.openai.internal.chat.ToolMessage.builder()
                                .toolCallId("call_42")
                                .content("{\"temp_c\":21,\"sky\":\"sunny\"}")
                                .build()))
                .tools(weatherTool)
                .build();

        var secondResponse = provider.generate(secondRequest, "ws-1");

        // Proxy correctly translated all three message types on the resume side.
        assertThat(capturedRequests).hasSize(2);
        List<ChatMessage> resumeMessages = capturedRequests.get(1).messages();
        assertThat(resumeMessages).hasSize(3);
        assertThat(resumeMessages.get(0)).isInstanceOf(UserMessage.class);
        var assistantTurn = (AiMessage) resumeMessages.get(1);
        assertThat(assistantTurn.toolExecutionRequests()).hasSize(1);
        assertThat(assistantTurn.toolExecutionRequests().getFirst().id()).isEqualTo("call_42");
        var toolResult = (ToolExecutionResultMessage) resumeMessages.get(2);
        assertThat(toolResult.id()).isEqualTo("call_42");
        assertThat(toolResult.text()).isEqualTo("{\"temp_c\":21,\"sky\":\"sunny\"}");

        // Final response is a standard text answer with stop finish_reason.
        var finalChoice = secondResponse.choices().getFirst();
        assertThat(finalChoice.finishReason()).isEqualTo("stop");
        assertThat(finalChoice.message().toolCalls()).isNullOrEmpty();
        assertThat(finalChoice.message().content()).isEqualTo("It's 21°C and sunny in Lisbon.");
        assertThat(secondResponse.usage().totalTokens()).isEqualTo(46);
    }

    @Test
    void propagatesStrictJsonSchemaToChatModelWhenRequestAsksForIt() {
        var chatModel = new StubChatModel(req -> ChatResponse.builder()
                .aiMessage(AiMessage.from("{}"))
                .metadata(ChatResponseMetadata.builder()
                        .id("resp_strict")
                        .modelName("gpt-4o-mini")
                        .finishReason(FinishReason.STOP)
                        .build())
                .build());
        var clientGenerator = Mockito.mock(OpenAIClientGenerator.class);
        var config = Mockito.mock(LlmProviderClientApiConfig.class);
        Mockito.when(clientGenerator.newResponsesApiChatModel(any(), anyBoolean())).thenReturn(chatModel);
        var provider = new LlmProviderOpenAiResponses(clientGenerator, config);

        var request = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")
                .addUserMessage("classify")
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON_SCHEMA)
                        .jsonSchema(JsonSchema.builder()
                                .name("classification")
                                .strict(true)
                                .schema(Map.of(
                                        "type", "object",
                                        "properties", Map.of("label", Map.of("type", "string")),
                                        "required", List.of("label")))
                                .build())
                        .build())
                .build();

        provider.generate(request, "ws-1");

        var strictCaptor = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(clientGenerator).newResponsesApiChatModel(any(), strictCaptor.capture());
        assertThat(strictCaptor.getValue()).isTrue();
    }

    @Test
    void buildsLooseModelWhenRequestHasNoResponseFormat() {
        var chatModel = new StubChatModel(req -> ChatResponse.builder()
                .aiMessage(AiMessage.from("hi back"))
                .metadata(ChatResponseMetadata.builder()
                        .id("resp_x")
                        .modelName("gpt-4o-mini")
                        .finishReason(FinishReason.STOP)
                        .build())
                .build());
        var clientGenerator = Mockito.mock(OpenAIClientGenerator.class);
        var config = Mockito.mock(LlmProviderClientApiConfig.class);
        Mockito.when(clientGenerator.newResponsesApiChatModel(any(), anyBoolean())).thenReturn(chatModel);
        var provider = new LlmProviderOpenAiResponses(clientGenerator, config);
        var request = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")
                .addUserMessage("hi")
                .build();

        provider.generate(request, "ws-1");

        var strictCaptor = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(clientGenerator).newResponsesApiChatModel(any(), strictCaptor.capture());
        assertThat(strictCaptor.getValue()).isFalse();
    }

    /**
     * Builds a {@link LlmProviderOpenAiResponses} backed by mocked generator + config that return the
     * supplied stub models regardless of the strict flag. Used by tests that exercise the request/
     * response translation path without caring about strict-mode propagation.
     */
    private static LlmProviderOpenAiResponses providerWithStubs(ChatModel chatModel,
            StreamingChatModel streamingChatModel) {
        var generator = Mockito.mock(OpenAIClientGenerator.class);
        var config = Mockito.mock(LlmProviderClientApiConfig.class);
        Mockito.when(generator.newResponsesApiChatModel(any(), anyBoolean())).thenReturn(chatModel);
        Mockito.when(generator.newResponsesApiStreamingChatModel(any(), anyBoolean())).thenReturn(streamingChatModel);
        return new LlmProviderOpenAiResponses(generator, config);
    }

    /**
     * Stub StreamingChatModel that synchronously drives the supplied handler through a fixed sequence
     * of partial responses followed by onCompleteResponse. No threads, no executor — keeps the test
     * deterministic and free of timing concerns.
     */
    private static StreamingChatModel streamingModelEmitting(List<String> partials, ChatResponse complete) {
        return new StubStreamingChatModel((chatRequest, handler) -> {
            partials.forEach(handler::onPartialResponse);
            handler.onCompleteResponse(complete);
        });
    }

    /**
     * StreamingChatModel has no abstract methods (all defaults), so a lambda can't satisfy it.
     * This stub exposes a single hook — {@code script} — invoked by {@link #chat(ChatRequest,
     * StreamingChatResponseHandler)} so each test can scripting the partial/complete/error sequence
     * inline.
     */
    private record StubStreamingChatModel(
            BiConsumer<ChatRequest, StreamingChatResponseHandler> script) implements StreamingChatModel {
        @Override
        public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            script.accept(chatRequest, handler);
        }
    }

    /**
     * Non-streaming counterpart to {@link StubStreamingChatModel}. Like its sibling, ChatModel has
     * only default methods so a lambda can't satisfy the interface; the script receives the request
     * and returns the response, letting tests vary behavior by inspecting the input.
     */
    private record StubChatModel(java.util.function.Function<ChatRequest, ChatResponse> script)
            implements
                ChatModel {
        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            return script.apply(chatRequest);
        }
    }
}