package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import lombok.NonNull;

import java.util.function.Consumer;

/**
 * Bridges langchain4j's {@link StreamingChatResponseHandler} callbacks to the Chat-Completions
 * wire format expected by Opik's proxy clients, delegating the per-chunk translation to
 * {@link LlmProviderOpenAiResponsesMapper}.
 */
record OpenAiResponsesStreamingHandler(
        @NonNull ChatCompletionRequest request,
        @NonNull Consumer<ChatCompletionResponse> handleMessage,
        @NonNull Runnable handleClose,
        @NonNull Consumer<Throwable> handleError) implements StreamingChatResponseHandler {

    @Override
    public void onPartialResponse(String partial) {
        handleMessage.accept(LlmProviderOpenAiResponsesMapper.toPartialChunk(partial, request));
    }

    @Override
    public void onCompleteResponse(ChatResponse response) {
        handleMessage.accept(LlmProviderOpenAiResponsesMapper.toFinalChunk(response, request));
        handleClose.run();
    }

    @Override
    public void onError(Throwable error) {
        handleError.accept(error);
    }
}