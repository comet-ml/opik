package com.comet.opik.domain;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.utils.JsonUtils;
import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.internal.RetryUtils;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.server.ChunkedOutput;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

@Singleton
@Slf4j
public class ChatCompletionService {

    private static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";

    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderApiKeyService llmProviderApiKeyService;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Inject
    public ChatCompletionService(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig,
            @NonNull LlmProviderApiKeyService llmProviderApiKeyService) {
        this.llmProviderApiKeyService = llmProviderApiKeyService;
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.retryPolicy = newRetryPolicy();
    }

    private RetryUtils.RetryPolicy newRetryPolicy() {
        var retryPolicyBuilder = RetryUtils.retryPolicyBuilder();
        Optional.ofNullable(llmProviderClientConfig.getMaxAttempts()).ifPresent(retryPolicyBuilder::maxAttempts);
        Optional.ofNullable(llmProviderClientConfig.getJitterScale()).ifPresent(retryPolicyBuilder::jitterScale);
        Optional.ofNullable(llmProviderClientConfig.getBackoffExp()).ifPresent(retryPolicyBuilder::backoffExp);
        return retryPolicyBuilder
                .delayMillis(llmProviderClientConfig.getDelayMillis())
                .build();
    }

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        var openAiClient = getAndConfigureOpenAiClient(request, workspaceId);
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

    public ChunkedOutput<String> createAndStreamResponse(
            @NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        var openAiClient = getAndConfigureOpenAiClient(request, workspaceId);
        var chunkedOutput = new ChunkedOutput<String>(String.class, "\r\n");
        openAiClient.chatCompletion(request)
                .onPartialResponse(chatCompletionResponse -> send(chatCompletionResponse, chunkedOutput))
                .onComplete(() -> close(chunkedOutput))
                .onError(throwable -> handle(throwable, chunkedOutput))
                .execute();
        log.info("Created and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chunkedOutput;
    }

    private OpenAiClient getAndConfigureOpenAiClient(ChatCompletionRequest request, String workspaceId) {
        var llmProvider = getLlmProvider(request.model());
        var encryptedApiKey = getEncryptedApiKey(workspaceId, llmProvider);
        return newOpenAiClient(encryptedApiKey);
    }

    /**
     * The agreed requirement is to resolve the LLM provider and its API key based on the model.
     * Currently, only OPEN AI is supported, so model param is ignored.
     * No further validation is needed on the model, as it's just forwarded in the OPEN AI request and will be rejected
     * if not valid.
     */
    private LlmProvider getLlmProvider(String model) {
        return LlmProvider.OPEN_AI;
    }

    /**
     * Finding API keys isn't paginated at the moment, since only OPEN AI is supported.
     * Even in the future, the number of supported LLM providers per workspace is going to be very low.
     */
    private String getEncryptedApiKey(String workspaceId, LlmProvider llmProvider) {
        return llmProviderApiKeyService.find(workspaceId).content().stream()
                .filter(providerApiKey -> llmProvider.equals(providerApiKey.provider()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("API key not configured for LLM provider '%s'".formatted(
                        llmProvider.getValue())))
                .apiKey();
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
    private OpenAiClient newOpenAiClient(String encryptedApiKey) {
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
                .openAiApiKey(EncryptionUtils.decrypt(encryptedApiKey))
                .build();
    }

    private void send(Object item, ChunkedOutput<String> chunkedOutput) {
        if (chunkedOutput.isClosed()) {
            log.warn("Output stream is already closed");
            return;
        }
        try {
            chunkedOutput.write(JsonUtils.writeValueAsString(item));
        } catch (IOException ioException) {
            throw new UncheckedIOException(ioException);
        }
    }

    private void handle(Throwable throwable, ChunkedOutput<String> chunkedOutput) {
        log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);
        var errorMessage = new ErrorMessage(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
        if (throwable instanceof OpenAiHttpException openAiHttpException) {
            errorMessage = new ErrorMessage(openAiHttpException.code(), openAiHttpException.getMessage());
        }
        try {
            send(errorMessage, chunkedOutput);
        } catch (UncheckedIOException uncheckedIOException) {
            log.error("Failed to stream error message to client", uncheckedIOException);
        }
        close(chunkedOutput);
    }

    private void close(ChunkedOutput<String> chunkedOutput) {
        try {
            chunkedOutput.close();
        } catch (IOException ioException) {
            log.error("Failed to close output stream", ioException);
        }
    }
}
