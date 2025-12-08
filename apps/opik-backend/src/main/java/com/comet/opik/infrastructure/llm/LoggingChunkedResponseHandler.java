package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.ChunkedResponseHandler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.output.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * A wrapper around ChunkedResponseHandler that logs the complete assembled response
 * after streaming is finished, instead of logging each individual chunk.
 */
@Slf4j
public class LoggingChunkedResponseHandler
        implements
            StreamingResponseHandler<AiMessage>,
            StreamingChatResponseHandler {

    private final ChunkedResponseHandler delegate;
    private final StringBuilder accumulatedContent = new StringBuilder();
    private final String requestSummary;

    public LoggingChunkedResponseHandler(
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError,
            @NonNull String model,
            @NonNull String requestSummary) {
        this.delegate = new ChunkedResponseHandler(handleMessage, handleClose, handleError, model);
        this.requestSummary = requestSummary;
    }

    @Override
    public void onNext(@NonNull String content) {
        accumulatedContent.append(content);
        delegate.onNext(content);
    }

    @Override
    public void onComplete(@NonNull Response<AiMessage> response) {
        // Log the complete assembled response
        if (log.isDebugEnabled()) {
            log.debug(
                    "LLM Streaming Response Complete - Request: {}, Model: {}, Content: {}, InputTokens: {}, OutputTokens: {}, TotalTokens: {}",
                    requestSummary,
                    delegate.model(),
                    accumulatedContent.toString(),
                    response.tokenUsage().inputTokenCount(),
                    response.tokenUsage().outputTokenCount(),
                    response.tokenUsage().totalTokenCount());
        }

        delegate.onComplete(response);
    }

    @Override
    public void onPartialResponse(String s) {
        accumulatedContent.append(s);
        delegate.onPartialResponse(s);
    }

    @Override
    public void onCompleteResponse(ChatResponse chatResponse) {
        // Log the complete assembled response
        if (log.isDebugEnabled()) {
            log.debug(
                    "LLM Streaming Response Complete - Request: {}, Model: {}, Content: {}, InputTokens: {}, OutputTokens: {}, TotalTokens: {}",
                    requestSummary,
                    delegate.model(),
                    accumulatedContent.toString(),
                    chatResponse.tokenUsage().inputTokenCount(),
                    chatResponse.tokenUsage().outputTokenCount(),
                    chatResponse.tokenUsage().totalTokenCount());
        }

        delegate.onCompleteResponse(chatResponse);
    }

    @Override
    public void onError(@NonNull Throwable throwable) {
        // Log error with any accumulated content
        if (log.isDebugEnabled()) {
            log.debug("LLM Streaming Response Error - Request: {}, Model: {}, Partial Content: {}, Error: {}",
                    requestSummary,
                    delegate.model(),
                    accumulatedContent.toString(),
                    throwable.getMessage());
        }

        delegate.onError(throwable);
    }
}
