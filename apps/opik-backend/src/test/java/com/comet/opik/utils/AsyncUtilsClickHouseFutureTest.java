package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract of {@link AsyncUtils#fromClickHouseFuture}: a cancelled subscriber must not strand the
 * response the ClickHouse v2 client is still building, because nothing else will ever close it and its
 * pooled connection does not come back (OPIK-8576).
 */
class AsyncUtilsClickHouseFutureTest {

    private static final class CountingResponse implements AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    @Test
    @DisplayName("cancelled subscriber: the late response is closed, and the future was never cancelled")
    void cancelledSubscriberClosesTheLateResponse() {
        var response = new CountingResponse();
        var queryFuture = new CompletableFuture<CountingResponse>();

        var subscription = AsyncUtils.fromClickHouseFuture(() -> queryFuture).subscribe();
        subscription.dispose();

        // Suppressing the cancel is what keeps the future able to accept the value at all.
        assertThat(queryFuture.isCancelled()).isFalse();

        // The client's HTTP round trip finishes afterwards and publishes its response.
        assertThat(queryFuture.complete(response)).isTrue();

        assertThat(response.closes).hasValue(1);
    }

    @Test
    @DisplayName("plain Mono.fromFuture strands it: the value never arrives, so no discard handler can help")
    void plainFromFutureStrandsTheResponse() {
        var response = new CountingResponse();
        var queryFuture = new CompletableFuture<CountingResponse>();

        var subscription = Mono.fromFuture(() -> queryFuture)
                .doOnDiscard(AutoCloseable.class, AsyncUtils::closeQuietly)
                .subscribe();
        subscription.dispose();

        assertThat(queryFuture.isCancelled()).isTrue();
        // complete() is refused by an already-cancelled future, so the response is unreachable.
        assertThat(queryFuture.complete(response)).isFalse();
        assertThat(response.closes).hasValue(0);
    }

    @Test
    @DisplayName("uncancelled subscriber: the response is delivered unclosed, for the caller to consume")
    void deliveredResponseIsNotClosed() {
        var response = new CountingResponse();

        var delivered = AsyncUtils.fromClickHouseFuture(() -> CompletableFuture.completedFuture(response))
                .block();

        assertThat(delivered).isSameAs(response);
        assertThat(response.closes).hasValue(0);
    }

    @Test
    @DisplayName("a close that throws is swallowed: there is no caller left to take the exception")
    void closeQuietlySwallows() {
        AsyncUtils.closeQuietly(() -> {
            throw new IllegalStateException("close failed");
        });
    }
}
