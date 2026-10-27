package com.comet.opik.infrastructure.llm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private final Map<String, String> headers;

    private ProviderHeadersHttpClient(HttpClient delegate, Map<String, String> headers) {
        super(delegate);
        this.headers = headers;
    }

    /** {@code delegate}, whose every client adds {@code headers} to its requests; as it is when there are none. */
    public static HttpClientBuilder builder(@NonNull HttpClientBuilder delegate, Map<String, String> headers) {
        var usable = usable(headers);
        if (usable.isEmpty()) {
            return delegate;
        }
        return new DelegatingHttpClientBuilder(delegate) {
            @Override
            protected HttpClient wrap(HttpClient client) {
                return new ProviderHeadersHttpClient(client, usable);
            }
        };
    }

    /**
     * The headers that can be sent, by name in any case. Saving refuses the others, but a row stored before that may
     * still hold them: a blank name or a missing value is left out, and of names differing only in case, the one
     * sorting last wins.
     */
    private static Map<String, String> usable(Map<String, String> headers) {
        if (MapUtils.isEmpty(headers)) {
            return Map.of();
        }
        var usable = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.entrySet().stream()
                .filter(header -> {
                    if (StringUtils.isBlank(header.getKey()) || header.getValue() == null) {
                        // the name only: a value can carry a credential
                        log.warn("Leaving out a provider header without a name or a value, header '{}'",
                                header.getKey());
                        return false;
                    }
                    return true;
                })
                .sorted(Map.Entry.comparingByKey())
                .forEach(header -> usable.put(header.getKey(), header.getValue()));
        return Collections.unmodifiableMap(usable);
    }

    /** Sends the request with the provider's headers. */
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(withHeaders(request));
    }

    /** Sends the request with the provider's headers. */
    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
        return delegate.executeAsync(withHeaders(request));
    }

    /** Sends the request with the provider's headers. */
    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        delegate.execute(withHeaders(request), parser, listener);
    }

    /** Sends the request with the provider's headers. */
    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
        return delegate.stream(withHeaders(request), parser);
    }

    private HttpRequest withHeaders(HttpRequest request) {
        var merged = new LinkedHashMap<String, List<String>>();
        request.headers().forEach((name, values) -> {
            if (!headers.containsKey(name)) {
                merged.put(name, values);
            }
        });
        headers.forEach((name, value) -> merged.put(name, List.of(value)));
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
