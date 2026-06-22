package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.llm.DelegatingHttpClientBuilder;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import lombok.NonNull;

import java.util.Map;

/**
 * Decorates an {@link HttpClientBuilder} so the produced {@link HttpClient} is
 * wrapped with {@link InterceptingHttpClient} to apply Custom LLM-specific
 * request mutations (query params, auth headers, {@code {model}} substitution).
 *
 * <p>Timeout forwarding is inherited from {@link DelegatingHttpClientBuilder}; only the
 * {@link #wrap(HttpClient)} step is specific to this builder.
 */
class InterceptingHttpClientBuilder extends DelegatingHttpClientBuilder {

    private final Map<String, String> configuration;
    private final String apiKey;

    InterceptingHttpClientBuilder(@NonNull HttpClientBuilder delegate, Map<String, String> configuration,
            String apiKey) {
        super(delegate);
        this.configuration = configuration;
        this.apiKey = apiKey;
    }

    @Override
    protected HttpClient wrap(HttpClient delegate) {
        return new InterceptingHttpClient(delegate, configuration, apiKey);
    }
}
