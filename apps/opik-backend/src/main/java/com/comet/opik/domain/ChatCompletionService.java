package com.comet.opik.domain;

import com.comet.opik.domain.llmproviders.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.utils.JsonUtils;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.internal.RetryUtils;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ChunkedOutput;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

@Singleton
@Slf4j
public class ChatCompletionService {
    public static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";

    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderFactory llmProviderFactory;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Inject
    public ChatCompletionService(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig, LlmProviderFactory llmProviderFactory) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.llmProviderFactory = llmProviderFactory;
        this.retryPolicy = newRetryPolicy();
    }

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());

        ChatCompletionResponse chatCompletionResponse;
        try {
            chatCompletionResponse = retryPolicy.withRetry(() -> llmProviderClient.generate(request, workspaceId));
        } catch (RuntimeException runtimeException) {
            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw llmProviderClient.mapRuntimeException(runtimeException);
        }

        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chatCompletionResponse;
    }

    public ChunkedOutput<String> createAndStreamResponse(
            @NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());

        var chunkedOutput = new ChunkedOutput<String>(String.class, "\r\n");
        llmProviderClient.generateStream(
                request,
                workspaceId,
                getMessageHandler(chunkedOutput),
                getCloseHandler(chunkedOutput),
                getErrorHandler(chunkedOutput, llmProviderClient::mapThrowableToError));
        log.info("Created and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chunkedOutput;
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

    private <T> Consumer<T> getMessageHandler(ChunkedOutput<String> chunkedOutput) {
        return item -> {
            if (chunkedOutput.isClosed()) {
                log.warn("Output stream is already closed");
                return;
            }
            try {
                chunkedOutput.write(JsonUtils.writeValueAsString(item));
            } catch (IOException ioException) {
                throw new UncheckedIOException(ioException);
            }
        };
    }

    private Runnable getCloseHandler(ChunkedOutput<String> chunkedOutput) {
        return () -> {
            try {
                chunkedOutput.close();
            } catch (IOException ioException) {
                log.error("Failed to close output stream", ioException);
            }
        };
    }

    private Consumer<Throwable> getErrorHandler(
            ChunkedOutput<String> chunkedOutput, Function<Throwable, ErrorMessage> errorMapper) {
        return throwable -> {
            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);

            var errorMessage = errorMapper.apply(throwable);
            try {
                getMessageHandler(chunkedOutput).accept(errorMessage);
            } catch (UncheckedIOException uncheckedIOException) {
                log.error("Failed to stream error message to client", uncheckedIOException);
            }
            getCloseHandler(chunkedOutput).run();
        };
    }
}
