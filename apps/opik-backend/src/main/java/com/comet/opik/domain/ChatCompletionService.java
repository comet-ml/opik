package com.comet.opik.domain;

import com.comet.opik.domain.llmproviders.LlmProviderFactory;
import com.comet.opik.domain.llmproviders.LlmProviderStreamHandler;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.langchain4j.internal.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ChunkedOutput;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Optional;

@Singleton
@Slf4j
public class ChatCompletionService {
    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderFactory llmProviderFactory;
    private final LlmProviderStreamHandler streamHandler;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Inject
    public ChatCompletionService(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig,
            LlmProviderFactory llmProviderFactory, LlmProviderStreamHandler streamHandler) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.llmProviderFactory = llmProviderFactory;
        this.streamHandler = streamHandler;
        this.retryPolicy = newRetryPolicy();
    }

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());
        var chatCompletionResponse = retryPolicy.withRetry(() -> llmProviderClient.generate(request, workspaceId));
        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chatCompletionResponse;
    }

    public ChunkedOutput<String> createAndStreamResponse(
            @NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());
        var chunkedOutput = llmProviderClient.generateStream(request, workspaceId, streamHandler);
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
}
