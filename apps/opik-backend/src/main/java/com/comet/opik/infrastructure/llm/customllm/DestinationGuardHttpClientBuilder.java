package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.llm.DelegatingHttpClientBuilder;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;

/**
 * Re-checks the final request URL right before each outbound call, after any {@code {model}}
 * substitution, so a destination that changed since save time is still refused.
 */
class DestinationGuardHttpClientBuilder extends DelegatingHttpClientBuilder {

    private final ProviderBaseUrlGuard guard;

    DestinationGuardHttpClientBuilder(@NonNull HttpClientBuilder delegate, @NonNull ProviderBaseUrlGuard guard) {
        super(delegate);
        this.guard = guard;
    }

    @Override
    protected HttpClient wrap(HttpClient delegate) {
        return new HttpClient() {
            @Override
            public SuccessfulHttpResponse execute(HttpRequest request) {
                guard.validateResolved(request.url());
                return delegate.execute(request);
            }

            @Override
            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                try {
                    guard.validateResolved(request.url());
                } catch (RuntimeException exception) {
                    listener.onError(exception);
                    return;
                }
                delegate.execute(request, parser, listener);
            }
        };
    }
}
