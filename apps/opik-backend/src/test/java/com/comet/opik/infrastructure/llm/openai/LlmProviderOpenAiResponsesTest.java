package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style coverage for {@link LlmProviderOpenAiResponses#generateStream}: drives a stub
 * {@link StreamingChatModel} that emits a realistic partial/complete/error sequence and asserts on
 * the chunks that flow through the real {@link LlmProviderOpenAiResponsesMapper}.
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
        var provider = new LlmProviderOpenAiResponses(Mockito.mock(ChatModel.class), streamingModel);
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
        var provider = new LlmProviderOpenAiResponses(Mockito.mock(ChatModel.class), streamingModel);
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
}