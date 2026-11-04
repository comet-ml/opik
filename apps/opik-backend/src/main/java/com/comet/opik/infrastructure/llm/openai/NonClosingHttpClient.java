package com.comet.opik.infrastructure.llm.openai;

import com.openai.core.RequestOptions;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;

/**
 * The OpenAI SDK transport shared by every Responses API client, which none of them may close.
 *
 * <p>The SDK closes the transport it is given once the client built on it is closed or garbage collected, and closing
 * the shared one would empty its connection pool and stop its dispatcher for every other client. So {@link #close()}
 * does nothing, and the shared transport lives as long as the application.
 */
@RequiredArgsConstructor
final class NonClosingHttpClient implements HttpClient {

    private final @NonNull HttpClient delegate;

    @Override
    public HttpResponse execute(@NonNull HttpRequest request, @NonNull RequestOptions requestOptions) {
        return delegate.execute(request, requestOptions);
    }

    @Override
    public HttpResponse execute(@NonNull HttpRequest request) {
        return delegate.execute(request);
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(@NonNull HttpRequest request,
            @NonNull RequestOptions requestOptions) {
        return delegate.executeAsync(request, requestOptions);
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(@NonNull HttpRequest request) {
        return delegate.executeAsync(request);
    }

    /** Leaves the shared transport open: a client built on it closing must not close it for the others. */
    @Override
    public void close() {
        // the shared transport outlives every client built on it
    }
}
