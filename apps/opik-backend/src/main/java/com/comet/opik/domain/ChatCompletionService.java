package com.comet.opik.domain;

import com.comet.opik.domain.llmproviders.LlmProviderFactory;
import com.comet.opik.domain.llmproviders.LlmProviderStreamHandler;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ChunkedOutput;

@Singleton
@Slf4j
public class ChatCompletionService {
    private final LlmProviderFactory llmProviderFactory;
    private final LlmProviderStreamHandler streamHandler;

    @Inject
    public ChatCompletionService(LlmProviderFactory llmProviderFactory, LlmProviderStreamHandler streamHandler) {
        this.llmProviderFactory = llmProviderFactory;
        this.streamHandler = streamHandler;
    }

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());
        var chatCompletionResponse = llmProviderClient.generate(request, workspaceId);
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
}
