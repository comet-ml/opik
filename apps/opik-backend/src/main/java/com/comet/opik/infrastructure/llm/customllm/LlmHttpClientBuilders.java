package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.http.client.HttpClientBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;

/**
 * The langchain4j HTTP client builders every model is given explicitly, so all of them run on the two shared OkHttp
 * clients: one guarded, for destinations a user configured, and one for destinations the operator or Opik configured.
 */
@RequiredArgsConstructor
public class LlmHttpClientBuilders {

    private final @NonNull OkHttpClient guardedClient;
    private final @NonNull OkHttpClient unguardedClient;

    /** Most bytes read from a non-2xx response body. */
    private final int errorBodyMaxSize;

    /** For a destination a user configured: each address connected to must pass the destination guard. */
    public HttpClientBuilder guarded() {
        return new CustomLlmHttpClientBuilder(guardedClient, errorBodyMaxSize);
    }

    /** For a destination the operator or Opik configured, which may be internal. */
    public HttpClientBuilder unguarded() {
        return new CustomLlmHttpClientBuilder(unguardedClient, errorBodyMaxSize);
    }

    /** Guarded when a provider key sets its own base URL, which a user chose; else the operator's default endpoint. */
    public HttpClientBuilder forProviderBaseUrl(String baseUrl) {
        return StringUtils.isNotBlank(baseUrl) ? guarded() : unguarded();
    }
}
