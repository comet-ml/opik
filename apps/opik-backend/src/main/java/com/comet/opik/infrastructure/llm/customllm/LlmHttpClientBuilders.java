package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.http.client.HttpClientBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;

import java.time.Duration;

/**
 * The langchain4j HTTP client builder every model is given explicitly, so all of them run on the one shared OkHttp
 * client, whose every connection goes only to addresses the destination guard approves.
 */
@RequiredArgsConstructor
public class LlmHttpClientBuilders {

    private final @NonNull OkHttpClient client;

    /** Most bytes read from a non-2xx response body. */
    private final int errorBodyMaxSize;

    /** Longest a stream may stay silent once its headers arrived. */
    private final @NonNull Duration streamReadTimeout;

    public HttpClientBuilder builder() {
        return new CustomLlmHttpClientBuilder(client, errorBodyMaxSize, streamReadTimeout);
    }
}
