package com.comet.opik.domain.llmproviders;

import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.internal.RetryUtils;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ChunkedOutput;

@RequiredArgsConstructor
@Slf4j
public class OpenAi implements LlmProviderService {
    private static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";

    private final OpenAiClient openAiClient;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        ChatCompletionResponse chatCompletionResponse;
        try {
            chatCompletionResponse = retryPolicy.withRetry(() -> openAiClient.chatCompletion(request).execute());
        } catch (RuntimeException runtimeException) {
            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            if (runtimeException.getCause() instanceof OpenAiHttpException openAiHttpException) {
                if (openAiHttpException.code() >= 400 && openAiHttpException.code() <= 499) {
                    throw new ClientErrorException(openAiHttpException.getMessage(), openAiHttpException.code());
                }
                throw new ServerErrorException(openAiHttpException.getMessage(), openAiHttpException.code());
            }
            throw new InternalServerErrorException(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
        }
        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chatCompletionResponse;
    }

    @Override
    public ChunkedOutput<String> generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull LlmProviderStreamHandler streamHandler) {
        log.info("Creating and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        var chunkedOutput = new ChunkedOutput<String>(String.class, "\r\n");
        openAiClient.chatCompletion(request)
                .onPartialResponse(
                        chatCompletionResponse -> streamHandler.handleMessage(chatCompletionResponse, chunkedOutput))
                .onComplete(() -> streamHandler.handleClose(chunkedOutput))
                .onError(throwable -> streamHandler.handleError(throwable, chunkedOutput))
                .execute();
        log.info("Created and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chunkedOutput;
    }
}
