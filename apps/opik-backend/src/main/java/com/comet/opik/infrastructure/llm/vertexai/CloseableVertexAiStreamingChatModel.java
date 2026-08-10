package com.comet.opik.infrastructure.llm.vertexai;

import com.google.cloud.vertexai.VertexAI;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.NonNull;

import java.util.Set;

// Streaming counterpart to CloseableVertexAiChatModel: owns the VertexAI and closes it (caller closes on stream terminal).
class CloseableVertexAiStreamingChatModel implements StreamingChatModel, AutoCloseable {

    private final @NonNull StreamingChatModel delegate;
    private final @NonNull VertexAI vertexAI;

    CloseableVertexAiStreamingChatModel(@NonNull StreamingChatModel delegate, @NonNull VertexAI vertexAI) {
        this.delegate = delegate;
        this.vertexAI = vertexAI;
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        delegate.chat(chatRequest, handler);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public void close() {
        vertexAI.close();
    }

    VertexAI vertexAI() {
        return vertexAI;
    }
}
