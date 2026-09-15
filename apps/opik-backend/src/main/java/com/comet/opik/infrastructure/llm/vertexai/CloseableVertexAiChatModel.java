package com.comet.opik.infrastructure.llm.vertexai;

import com.google.genai.Client;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

// Owns the genai Client and closes it; the langchain4j model can't (it keeps the client private and is not closeable).
@Slf4j
@RequiredArgsConstructor
@Accessors(fluent = true)
class CloseableVertexAiChatModel implements ChatModel, AutoCloseable {

    private final @NonNull ChatModel delegate;
    @Getter
    private final @NonNull Client client;

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
            client.close();
        } catch (Exception e) {
            log.warn("Failed to close Vertex AI client", e);
        }
        // A no-op with today's delegate, but only it can release anything it owns.
        try {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.warn("Failed to close the delegate Vertex AI model", e);
        }
    }
}
