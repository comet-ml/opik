package com.comet.opik.infrastructure.llm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Adds a provider's configured headers to every request, each replacing a header of the same name in any case, as
 * LangChain4j's own custom headers do. Added here, below LangChain4j's request logging, so that their values are never
 * logged.
 */
@Slf4j
public class ProviderHeadersHttpClient extends DelegatingHttpClient {

    /** Case-insensitive by name, as HTTP header names are. */
    private final Map<String, String> normalizedProviderHeaders;

    private ProviderHeadersHttpClient(HttpClient delegate, Map<String, String> normalizedProviderHeaders) {
        super(delegate);
        this.normalizedProviderHeaders = normalizedProviderHeaders;
    }

    /** {@code delegate}, whose every client adds {@code headers} to its requests; as it is when there are none. */
    public static HttpClientBuilder builder(@NonNull HttpClientBuilder delegate,
            @Nullable Map<String, String> headers) {
        var normalizedProviderHeaders = ProviderHeaders.normalized(headers);
        if (normalizedProviderHeaders.isEmpty()) {
            return delegate;
        }
        return new DelegatingHttpClientBuilder(delegate) {
            @Override
            protected HttpClient wrap(@NonNull HttpClient client) {
                return new ProviderHeadersHttpClient(client, normalizedProviderHeaders);
            }
        };
    }

    /** Sends the request with the provider's headers. */
    @Override
    public SuccessfulHttpResponse execute(@NonNull HttpRequest request) {
        return delegate.execute(withHeaders(request));
    }

    /** Sends the request with the provider's headers. */
    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(@NonNull HttpRequest request) {
        return delegate.executeAsync(withHeaders(request));
    }

    /** Sends the request with the provider's headers. */
    @Override
    public void execute(@NonNull HttpRequest request, @NonNull ServerSentEventParser parser,
            @NonNull ServerSentEventListener listener) {
        delegate.execute(withHeaders(request), parser, listener);
    }

    /** Sends the request with the provider's headers. */
    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(@NonNull HttpRequest request,
            @NonNull ServerSentEventParser parser) {
        return delegate.stream(withHeaders(request), parser);
    }

    private HttpRequest withHeaders(HttpRequest request) {
        var merged = new LinkedHashMap<String, List<String>>();
        request.headers().forEach((name, values) -> {
            if (!normalizedProviderHeaders.containsKey(name)) {
                merged.put(name, values);
            }
        });
        normalizedProviderHeaders.forEach((name, value) -> merged.put(name, List.of(value)));
        return HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(merged)
                .formDataFields(request.formDataFields())
                .formDataFiles(request.formDataFiles())
                .body(request.body())
                .build();
    }
}
