package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Re-checks the final request URL right before each outbound call, after any {@code {model}} substitution, so a
 * destination that changed since save time is still refused.
 */
@RequiredArgsConstructor
class DestinationGuardHttpClient implements HttpClient {

    private final @NonNull HttpClient delegate;
    private final @NonNull ProviderBaseUrlGuard guard;

    @Override
    public SuccessfulHttpResponse execute(@NonNull HttpRequest request) {
        guard.validateRequest(request.url());
        return delegate.execute(request);
    }

    @Override
    public void execute(@NonNull HttpRequest request, @NonNull ServerSentEventParser parser,
            @NonNull ServerSentEventListener listener) {
        try {
            guard.validateRequest(request.url());
        } catch (ProviderRequestRefusedException exception) {
            listener.onError(exception);
            return;
        }
        delegate.execute(request, parser, listener);
    }
}
