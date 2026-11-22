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

import java.util.Set;

/**
 * A Vertex AI chat model that inlines remote media before each call, and holds the Gen AI {@link Client} it is built
 * on. Nothing to close: the client runs on the HTTP client every Vertex AI call shares, see
 * {@link VertexAIClientGenerator}.
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
class VertexAiChatModel implements ChatModel {

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
}
