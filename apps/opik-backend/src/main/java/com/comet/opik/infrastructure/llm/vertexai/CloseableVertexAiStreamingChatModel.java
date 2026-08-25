package com.comet.opik.infrastructure.llm.vertexai;

import com.google.cloud.vertexai.VertexAI;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

// Streaming counterpart to CloseableVertexAiChatModel: owns the VertexAI and closes it (caller closes on stream terminal).
@Slf4j
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

    // Best-effort: a close failure must never surface on an otherwise-successful call.
    @Override
    public void close() {
        try {
            vertexAI.close();
        } catch (Exception e) {
            log.warn("Failed to close Vertex AI streaming client", e);
        }
        // The streaming delegate owns a per-instance executor; only its close() shuts it down.
        try {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.warn("Failed to close the delegate Vertex AI streaming model", e);
        }
    }

    VertexAI vertexAI() {
        return vertexAI;
    }
}
