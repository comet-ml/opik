package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.Objects;

/**
 * Builds a {@link CustomLlmHttpClient} on the shared OkHttp client, so every provider's calls share its connections.
 */
@RequiredArgsConstructor
class CustomLlmHttpClientBuilder implements HttpClientBuilder {

    private final @NonNull OkHttpClient client;
    private Duration connectTimeout;
    private Duration readTimeout;

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    @Override
    public HttpClient build() {
        // as with the JDK client, an unset timeout doesn't apply, and the read timeout also bounds sending the request
        var requestTimeout = Objects.requireNonNullElse(readTimeout, Duration.ZERO);
        return new CustomLlmHttpClient(client.newBuilder()
                .connectTimeout(Objects.requireNonNullElse(connectTimeout, Duration.ZERO))
                .readTimeout(requestTimeout)
                .writeTimeout(requestTimeout)
                .build());
    }
}
