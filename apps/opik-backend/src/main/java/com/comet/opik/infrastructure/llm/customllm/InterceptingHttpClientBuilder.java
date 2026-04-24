package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Map;

/**
 * Decorates an {@link HttpClientBuilder} so the produced {@link HttpClient} is
 * wrapped with {@link InterceptingHttpClient} to apply Custom LLM-specific
 * request mutations (query params, auth headers, {@code {model}} substitution).
 *
 * <p>Timeout setter calls are forwarded to the delegate so existing
 * configuration behavior (LC4j connect/read timeouts) is preserved intact.
 */
@RequiredArgsConstructor
class InterceptingHttpClientBuilder implements HttpClientBuilder {

    private final @NonNull HttpClientBuilder delegate;
    private final Map<String, String> configuration;
    private final String apiKey;

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
    public HttpClient build() {
        return new InterceptingHttpClient(delegate.build(), configuration, apiKey);
    }
}
