package com.comet.opik.domain.llmproviders;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.server.ChunkedOutput;

import java.util.Optional;

@Slf4j
public class OpenAi implements LlmProviderService {
    private static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";

    private final LlmProviderClientConfig llmProviderClientConfig;
    private final OpenAiClient openAiClient;

    @Inject
    public OpenAi(LlmProviderClientConfig llmProviderClientConfig, String apiKey) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.openAiClient = newOpenAiClient(apiKey);
    }

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        ChatCompletionResponse chatCompletionResponse;
        try {
            chatCompletionResponse = openAiClient.chatCompletion(request).execute();
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
                .onError(streamHandler.getErrorHandler(this::errorMapper, chunkedOutput))
                .execute();
        log.info("Created and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chunkedOutput;
    }

    /**
     * Initially, only OPEN AI is supported, so no need for a more sophisticated client resolution to start with.
     * At the moment, openai4j client and also langchain4j wrappers, don't support dynamic API keys. That can imply
     * an important performance penalty for next phases. The following options should be evaluated:
     * - Cache clients, but can be unsafe.
     * - Find and evaluate other clients.
     * - Implement our own client.
     * TODO as part of : <a href="https://comet-ml.atlassian.net/browse/OPIK-522">OPIK-522</a>
     */
    private OpenAiClient newOpenAiClient(String apiKey) {
        var openAiClientBuilder = OpenAiClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .ifPresent(baseUrl -> {
                    if (StringUtils.isNotBlank(baseUrl)) {
                        openAiClientBuilder.baseUrl(baseUrl);
                    }
                });
        Optional.ofNullable(llmProviderClientConfig.getCallTimeout())
                .ifPresent(callTimeout -> openAiClientBuilder.callTimeout(callTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> openAiClientBuilder.connectTimeout(connectTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                .ifPresent(readTimeout -> openAiClientBuilder.readTimeout(readTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getWriteTimeout())
                .ifPresent(writeTimeout -> openAiClientBuilder.writeTimeout(writeTimeout.toJavaDuration()));
        return openAiClientBuilder
                .openAiApiKey(apiKey)
                .build();
    }

    private ErrorMessage errorMapper(Throwable throwable) {
        if (throwable instanceof OpenAiHttpException openAiHttpException) {
            return new ErrorMessage(openAiHttpException.code(), openAiHttpException.getMessage());
        }

        return new ErrorMessage(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
    }
}
