package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.llm.DelegatingHttpClientBuilder;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import lombok.NonNull;

/**
 * Builds a {@link DestinationGuardHttpClient} around the delegate's client.
 */
class DestinationGuardHttpClientBuilder extends DelegatingHttpClientBuilder {

    private final ProviderBaseUrlGuard guard;

    DestinationGuardHttpClientBuilder(@NonNull HttpClientBuilder delegate, @NonNull ProviderBaseUrlGuard guard) {
        super(delegate);
        this.guard = guard;
    }

    @Override
    protected HttpClient wrap(@NonNull HttpClient delegate) {
        return new DestinationGuardHttpClient(delegate, guard);
    }
}
