package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The contract of {@link AsyncUtils#usingClickHouseFuture}: the ClickHouse v2 client's response is closed
 * exactly once on every path, including the ones where the subscriber has already walked away. Nothing else
 * will close it, and the pooled connection it holds does not come back (OPIK-8576).
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
    @DisplayName("cancelled before the response arrives: it is closed when it turns up, and the future was never cancelled")
    void cancelledBeforeDelivery() {
        var response = new CountingResponse();
        var queryFuture = new CompletableFuture<CountingResponse>();

        var subscription = AsyncUtils
                .usingClickHouseFuture(() -> queryFuture, r -> Mono.just(1L))
                .subscribe();
        subscription.dispose();

        // Suppressing the cancel is what keeps the future able to accept the value at all.
        assertThat(queryFuture.isCancelled()).isFalse();
        // The client's HTTP round trip finishes afterwards and publishes its response.
        assertThat(queryFuture.complete(response)).isTrue();

        assertThat(response.closes).hasValue(1);
    }

    @Test
    @DisplayName("cancelled while the response is being consumed: it is still closed")
    void cancelledAfterDelivery() {
        var response = new CountingResponse();

        var subscription = AsyncUtils
                .usingClickHouseFuture(() -> CompletableFuture.completedFuture(response), r -> Mono.never())
                .subscribe();
        subscription.dispose();

        // The cleanup is dispatched to boundedElastic because closing is I/O, so this one is not immediate.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(response.closes).hasValue(1));
    }

    @Test
    @DisplayName("normal completion: the value is emitted and the response closed exactly once")
    void normalCompletion() {
        var response = new CountingResponse();

        var written = AsyncUtils
                .usingClickHouseFuture(() -> CompletableFuture.completedFuture(response), r -> Mono.just(42L))
                .block();

        assertThat(written).isEqualTo(42L);
        assertThat(response.closes).hasValue(1);
    }

    @Test
    @DisplayName("the consumer fails: the response is still closed, and the failure still surfaces")
    void consumerFails() {
        var response = new CountingResponse();

        assertThatThrownBy(() -> AsyncUtils
                .usingClickHouseFuture(() -> CompletableFuture.completedFuture(response),
                        r -> Mono.error(new IllegalStateException("mapping failed")))
                .block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mapping failed");

        assertThat(response.closes).hasValue(1);
    }

    @Test
    @DisplayName("why a discard handler alone is not the fix: a cancelled future hands its value to nobody")
    void plainFromFutureStrandsTheResponse() {
        var response = new CountingResponse();
        var queryFuture = new CompletableFuture<CountingResponse>();
        var closes = new AtomicInteger();

        var subscription = Mono.fromFuture(() -> queryFuture)
                .doOnDiscard(AutoCloseable.class, ignored -> closes.incrementAndGet())
                .subscribe();
        subscription.dispose();

        assertThat(queryFuture.isCancelled()).isTrue();
        // complete() is refused by an already-cancelled future, so the response is unreachable.
        assertThat(queryFuture.complete(response)).isFalse();
        assertThat(closes).hasValue(0);
        assertThat(response.closes).hasValue(0);
    }
}
