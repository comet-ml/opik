package com.comet.opik.infrastructure.llm.antropic;

import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;

@RequiredArgsConstructor
class AnthropicStreamedChatModel implements ChatModel {

    private final @NonNull StreamingChatModel delegate;
    private final @NonNull Duration idleTimeout;
    private final @NonNull Duration callTimeout;

    // The publisher rather than a StreamingChatResponseHandler: cancelling its subscription aborts the HTTP request
    // even while the model thinks without sending text, so a timed-out judge stops writing and billing before the
    // retry starts. A handler only gets a cancel handle with the first text or tool call.
    @Override
    public ChatResponse chat(@NonNull ChatRequest chatRequest) {
        return JdkFlowAdapter.flowPublisherToFlux(delegate.chat(chatRequest))
                .timeout(idleTimeout, Mono.error(() -> new TimeoutException(
                        "Anthropic sent nothing for %s".formatted(idleTimeout))))
                .ofType(CompleteResponse.class)
                .map(CompleteResponse::chatResponse)
                .single()
                .timeout(callTimeout, Mono.error(() -> new TimeoutException(
                        "Anthropic did not finish the answer within %s".formatted(callTimeout))))
                .block();
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
