package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.llm.RemoteMediaInliner;
import com.google.genai.Client;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

// Streaming counterpart to CloseableVertexAiChatModel; the caller closes it on the stream terminal.
@Slf4j
@RequiredArgsConstructor
@Accessors(fluent = true)
class CloseableVertexAiStreamingChatModel implements StreamingChatModel, AutoCloseable {

    private final @NonNull StreamingChatModel delegate;
    @Getter
    private final @NonNull Client client;
    /** The calls {@link #client} makes, and only its; see {@link #cancel()}. */
    private final @NonNull CancellableCalls calls;
    private final @NonNull RemoteMediaInliner remoteMediaInliner;

    /** Ends the stream's call, which also ends a read blocked on it: the SDK's stream has no cancel of its own. */
    void cancel() {
        calls.cancelAll();
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        delegate.chat(chatRequest.toBuilder()
                .messages(remoteMediaInliner.inline(chatRequest.messages()))
                .build(), handler);
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
            log.warn("Failed to close the delegate Vertex AI streaming model", e);
        }
    }
}
