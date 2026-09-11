package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import lombok.NonNull;

import java.util.function.Consumer;

/**
 * Shared helper for OpenAI-compatible streaming requests with logging.
 * Eliminates code duplication between OpenAI and CustomLlm providers.
 */
public class OpenAiStreamingHelper {

    /**
     * Execute a streaming chat completion request with logging support.
     *
     * @param openAiClient The OpenAI client to use
     * @param request The chat completion request
     * @param handleMessage Callback for each partial response
     * @param handleClose Callback when streaming completes
     * @param handleError Callback for errors
     */
    public static void executeStreamingRequest(
            @NonNull OpenAiClient openAiClient,
            @NonNull ChatCompletionRequest request,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {

        // Create a simple summary of the request for logging
        String requestSummary = String.format("model=%s, messages=%d",
                request.model(),
                request.messages() != null ? request.messages().size() : 0);

        // Create reusable logger for accumulating and logging the complete response
        StreamingResponseLogger logger = new StreamingResponseLogger(requestSummary, request.model());

        openAiClient.chatCompletion(request)
                .onPartialResponse(response -> {
                    // Extract and accumulate content for logging
                    if (response.choices() != null && !response.choices().isEmpty()) {
                        var delta = response.choices().getFirst().delta();
                        if (delta != null && delta.content() != null) {
                            logger.appendContent(delta.content());
                        }
                    }
                    // Log complete response if this is the final message (has usage info)
                    if (response.usage() != null) {
                        logger.logComplete(response);
                    }
                    handleMessage.accept(response);
                })
                .onComplete(handleClose)
                .onError(throwable -> {
                    logger.logError(throwable);
                    handleError.accept(throwable);
                })
                .execute();
    }
}
