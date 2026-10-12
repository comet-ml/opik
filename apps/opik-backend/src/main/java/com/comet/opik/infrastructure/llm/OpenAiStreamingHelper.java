package com.comet.opik.infrastructure.llm;

import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import lombok.NonNull;

import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

        var failedInStream = new AtomicBoolean();

        openAiClient.chatCompletion(request)
                .onRawPartialResponse(rawResponse -> {
                    if (failedInStream.get()) {
                        return;
                    }
                    var inStreamError = findInStreamError(rawResponse.rawServerSentEvent());
                    if (inStreamError.isPresent()) {
                        failedInStream.set(true);
                        logger.logError(inStreamError.get());
                        handleError.accept(inStreamError.get());
                        return;
                    }
                    var response = rawResponse.parsedResponse();
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
                .onComplete(() -> {
                    if (!failedInStream.get()) {
                        handleClose.run();
                    }
                })
                .onError(throwable -> {
                    logger.logError(throwable);
                    handleError.accept(throwable);
                })
                .execute();
    }

    // Once the stream has started, OpenRouter reports a failure as a plain data chunk with a top-level "error" object.
    // langchain4j only fails on an "event: error" event, so it would parse this chunk as an empty answer. Raised the
    // way langchain4j raises its own SSE errors, so the provider's error mapper reads the chunk like an error body.
    private static Optional<RuntimeException> findInStreamError(ServerSentEvent event) {
        if (event == null || event.data() == null || !event.data().contains("\"error\"")) {
            return Optional.empty();
        }
        try {
            return Optional.of(JsonUtils.getJsonNodeFromString(event.data()))
                    .filter(chunk -> chunk.path("error").isObject())
                    .map(chunk -> new RuntimeException(event.data()));
        } catch (UncheckedIOException e) {
            return Optional.empty();
        }
    }
}
