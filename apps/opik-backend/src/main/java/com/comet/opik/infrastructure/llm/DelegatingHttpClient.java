package com.comet.opik.infrastructure.llm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Base {@link HttpClient} decorator that forwards every call, the asynchronous and publisher ones included, to a
 * delegate. Without the forwarding, a decorator overriding only the synchronous and listener calls would answer the
 * others with LangChain4j's not-supported defaults instead of reaching the transport. Subclasses override the calls
 * they decorate.
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class DelegatingHttpClient implements HttpClient {

    /** The decorated client every call is forwarded to. */
    protected final @NonNull HttpClient delegate;

    /** Forwards the synchronous call to the {@link #delegate}. */
    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(request);
    }

    /** Forwards the asynchronous call to the {@link #delegate}. */
    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
        return delegate.executeAsync(request);
    }

    /** Forwards the listener-based streaming call to the {@link #delegate}. */
    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        delegate.execute(request, parser, listener);
    }

    /** Forwards the publisher-based streaming call to the {@link #delegate}. */
    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(HttpRequest request, ServerSentEventParser parser) {
        return delegate.stream(request, parser);
    }

    /**
     * The outcome of {@code future}, or when it fails, the outcome of the future {@code recovery} returns for the
     * failure; a recovery that throws fails the result with that exception. Cancelling the result cancels whichever
     * of the two futures is in flight, so the call behind it is cancelled as it would be without the decorator.
     */
    protected static CompletableFuture<SuccessfulHttpResponse> recoverAsync(
            @NonNull CompletableFuture<SuccessfulHttpResponse> future,
            @NonNull Function<Throwable, CompletableFuture<SuccessfulHttpResponse>> recovery) {
        var result = new CompletableFuture<SuccessfulHttpResponse>();
        var inFlight = new AtomicReference<>(future);
        result.whenComplete((response, throwable) -> {
            if (result.isCancelled()) {
                inFlight.get().cancel(true);
            }
        });
        future.whenComplete((response, throwable) -> {
            if (throwable == null) {
                result.complete(response);
                return;
            }
            if (result.isDone()) {
                return;
            }
            CompletableFuture<SuccessfulHttpResponse> next;
            try {
                next = recovery.apply(unwrap(throwable));
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                return;
            }
            inFlight.set(next);
            // a cancel that raced the set above saw the first future, so it's applied here
            if (result.isCancelled()) {
                next.cancel(true);
                return;
            }
            next.whenComplete((nextResponse, nextThrowable) -> {
                if (nextThrowable == null) {
                    result.complete(nextResponse);
                } else {
                    result.completeExceptionally(unwrap(nextThrowable));
                }
            });
        });
        return result;
    }

    /** The failure itself, without the {@link CompletionException} a dependent stage wraps it in. */
    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
    }
}
