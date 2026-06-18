package com.comet.opik.infrastructure.llm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Duration;

/**
 * Base {@link HttpClientBuilder} that forwards timeout configuration to a delegate builder and wraps
 * the {@link HttpClient} it produces. Subclasses only implement {@link #wrap(HttpClient)} to apply
 * their specific decoration; the delegation/forwarding logic is shared here so each decorator does not
 * re-implement it.
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class DelegatingHttpClientBuilder implements HttpClientBuilder {

    private final @NonNull HttpClientBuilder delegate;

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        delegate.connectTimeout(connectTimeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        delegate.readTimeout(readTimeout);
        return this;
    }

    @Override
    public final HttpClient build() {
        return wrap(delegate.build());
    }

    /**
     * Wraps the {@link HttpClient} produced by the delegate builder with this builder's decoration.
     */
    protected abstract HttpClient wrap(HttpClient delegate);
}
