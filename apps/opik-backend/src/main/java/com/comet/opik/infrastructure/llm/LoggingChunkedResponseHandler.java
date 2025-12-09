package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.ChunkedResponseHandler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * A wrapper around ChunkedResponseHandler that logs the complete assembled response
 * after streaming is finished, instead of logging each individual chunk.
 * Uses StreamingResponseLogger for the actual logging logic (DRY principle).
 */
@Slf4j
public class LoggingChunkedResponseHandler
        implements
            StreamingResponseHandler<AiMessage>,
            StreamingChatResponseHandler {

    private final ChunkedResponseHandler delegate;
    private final StreamingResponseLogger logger;

    public LoggingChunkedResponseHandler(
            @NonNull ChunkedResponseHandler delegate,
            @NonNull StreamingResponseLogger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    @Override
    public void onNext(@NonNull String content) {
        logger.appendContent(content);
        delegate.onNext(content);
    }

    @Override
    public void onComplete(@NonNull Response<AiMessage> response) {
        logger.logComplete(
                response.tokenUsage().inputTokenCount(),
                response.tokenUsage().outputTokenCount(),
                response.tokenUsage().totalTokenCount());
        delegate.onComplete(response);
    }

    @Override
    public void onPartialResponse(String s) {
        logger.appendContent(s);
        delegate.onPartialResponse(s);
    }

    @Override
    public void onCompleteResponse(ChatResponse chatResponse) {
        logger.logComplete(
                chatResponse.tokenUsage().inputTokenCount(),
                chatResponse.tokenUsage().outputTokenCount(),
                chatResponse.tokenUsage().totalTokenCount());
        delegate.onCompleteResponse(chatResponse);
    }

    @Override
    public void onError(@NonNull Throwable throwable) {
        logger.logError(throwable);
        delegate.onError(throwable);
    }
}
