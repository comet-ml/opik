package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.llm.DelegatingHttpClient;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;
import mutiny.zero.ZeroPublisher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Re-checks the final request URL right before each outbound call, after any {@code {model}} substitution, so a
 * destination that changed since save time is still refused.
 */
class DestinationGuardHttpClient extends DelegatingHttpClient {

    /** Checks each request URL before it's forwarded. */
    private final @NonNull ProviderBaseUrlGuard guard;

    /** Guards every call made through {@code delegate} with {@code guard}. */
    DestinationGuardHttpClient(@NonNull HttpClient delegate, @NonNull ProviderBaseUrlGuard guard) {
        super(delegate);
        this.guard = guard;
    }

    /** Throws {@link ProviderRequestRefusedException} for a refused URL. */
    @Override
    public SuccessfulHttpResponse execute(@NonNull HttpRequest request) {
        guard.validateRequest(request.url());
        return delegate.execute(request);
    }

    /** Fails the future with {@link ProviderRequestRefusedException} for a refused URL. */
    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(@NonNull HttpRequest request) {
        try {
            guard.validateRequest(request.url());
        } catch (ProviderRequestRefusedException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return delegate.executeAsync(request);
    }

    /** Reports {@link ProviderRequestRefusedException} to the listener for a refused URL. */
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

    /**
     * Checks the URL on every subscription, as each one makes the call, and fails that subscription with
     * {@link ProviderRequestRefusedException} for a refused URL.
     */
    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(@NonNull HttpRequest request,
            @NonNull ServerSentEventParser parser) {
        return subscriber -> {
            try {
                guard.validateRequest(request.url());
            } catch (ProviderRequestRefusedException exception) {
                ZeroPublisher.<HttpStreamingEvent>fromFailure(exception).subscribe(subscriber);
                return;
            }
            delegate.stream(request, parser).subscribe(subscriber);
        };
    }
}
