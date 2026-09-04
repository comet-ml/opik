package com.comet.opik.infrastructure.llm.vertexai;

import com.google.cloud.vertexai.VertexAI;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

// Owns the VertexAI and closes it; the langchain4j model can't (its two-arg ctor nulls its handle, so its close() is a no-op).
@Slf4j
class CloseableVertexAiChatModel implements ChatModel, AutoCloseable {

    private final @NonNull ChatModel delegate;
    private final @NonNull VertexAI vertexAI;

    CloseableVertexAiChatModel(@NonNull ChatModel delegate, @NonNull VertexAI vertexAI) {
        this.delegate = delegate;
        this.vertexAI = vertexAI;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return delegate.chat(chatRequest);
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
            log.warn("Failed to close Vertex AI client", e);
        }
        // Symmetry: a no-op today, but the delegate is the only thing that can release resources it may own.
        try {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.warn("Failed to close the delegate Vertex AI model", e);
        }
    }

    VertexAI vertexAI() {
        return vertexAI;
    }
}
