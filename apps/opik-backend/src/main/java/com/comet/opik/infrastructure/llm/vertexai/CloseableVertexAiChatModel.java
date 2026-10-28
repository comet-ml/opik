package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.llm.RemoteMediaInliner;
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

// Holds the genai Client the langchain4j model is built on, and closes the model; see close().
@Slf4j
@RequiredArgsConstructor
@Accessors(fluent = true)
class CloseableVertexAiChatModel implements ChatModel, AutoCloseable {

    private final @NonNull ChatModel delegate;
    @Getter
    private final @NonNull Client client;
    private final @NonNull RemoteMediaInliner remoteMediaInliner;

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return delegate.chat(chatRequest.toBuilder()
                .messages(remoteMediaInliner.inline(chatRequest.messages()))
                .build());
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

    /**
     * Closes the delegate only. The client is left open: closing it would shut the dispatcher and empty the connection
     * pool that every Vertex AI call shares, see {@link VertexAIClientGenerator}, and it holds nothing else.
     */
    @Override
    public void close() {
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
