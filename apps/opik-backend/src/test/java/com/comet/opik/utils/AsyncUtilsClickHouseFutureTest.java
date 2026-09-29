package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract of {@link AsyncUtils#usingClickHouseFuture}: the ClickHouse v2 client's response is closed
 * exactly once on every path, including the ones where the subscriber has already walked away. Nothing else
 * closes it, and the pooled connection it holds does not come back (OPIK-8576).
 *
 * <p>Unit rather than integration by necessity: every case here turns on a subscriber cancelling at a
 * specific instant relative to the future completing, which no API-level call can schedule. The end-to-end
 * counterpart is {@code ClickHouseHealthCheckConnectionReleaseTest}, which proves against a real ClickHouse
 * that abandoned operations return their connections to the pool.
 */
class AsyncUtilsClickHouseFutureTest {

    private static final long AWAIT_SECONDS = 5;

    private static final class CountingResponse implements AutoCloseable {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    @Test
    @DisplayName("cancelled before the response arrives: closed when it turns up, future never cancelled")
    void cancelledBeforeDelivery() {
        var response = new CountingResponse();
        var queryFuture = new CompletableFuture<CountingResponse>();

        var subscription = AsyncUtils.usingClickHouseFuture(() -> queryFuture, ignored -> 1L).subscribe();
        subscription.dispose();

        // Suppressing the cancel is what keeps the future able to accept the value at all.
        assertThat(queryFuture.isCancelled()).isFalse();
        // The client's HTTP round trip finishes afterwards and publishes its response.
        assertThat(queryFuture.complete(response)).isTrue();

        assertThat(response.closeCount).hasValue(1);
    }

    @Test
    @DisplayName("cancelled while the response is being consumed: it is still closed")
    void cancelledAfterDelivery() throws Exception {
        var response = new CountingResponse();
        var consuming = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        var subscription = AsyncUtils.usingClickHouseFuture(
                () -> CompletableFuture.completedFuture(response),
                ignored -> {
                    consuming.countDown();
                    awaitQuietly(release);
                    return 1L;
                },
                Schedulers.boundedElastic()).subscribe();
        try {
            assertThat(consuming.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            subscription.dispose();

            assertThat(response.closeCount).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("normal completion: the value is emitted and the response closed exactly once")
    void normalCompletion() {
        var response = new CountingResponse();

        var written = AsyncUtils
                .usingClickHouseFuture(() -> CompletableFuture.completedFuture(response), ignored -> 42L)
                .block();

        assertThat(written).isEqualTo(42L);
        assertThat(response.closeCount).hasValue(1);
    }

    @Test
    @DisplayName("the consumer fails: the response is still closed, and the failure still surfaces")
    void consumerFails() {
        var response = new CountingResponse();

        assertThatThrownBy(() -> AsyncUtils
                .usingClickHouseFuture(() -> CompletableFuture.completedFuture(response), ignored -> {
                    throw new IllegalStateException("mapping failed");
                })
                .block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mapping failed");

        assertThat(response.closeCount).hasValue(1);
    }

    @Test
    @DisplayName("a close that fails does not fail the operation - the rows are already written")
    void closeFailureDoesNotFailTheOperation() {
        AutoCloseable failsToClose = () -> {
            throw new IllegalStateException("close failed");
        };

        var written = AsyncUtils
                .usingClickHouseFuture(() -> CompletableFuture.completedFuture(failsToClose), ignored -> 7L)
                .block();

        // Deliberate: the server has already written the rows and the count has already been read off the
        // response. Failing here would turn a connection-cleanup problem into a spurious insert failure,
        // which callers retry - and a retried bulk insert writes the rows twice. It is logged instead. The
        // health check is the opposite case and reports unhealthy, because reporting connection health is
        // that probe's entire job.
        assertThat(written).isEqualTo(7L);
    }

    @Test
    @DisplayName("why a discard handler alone is not the fix: a cancelled future hands its value to nobody")
    void plainFromFutureStrandsTheResponse() {
        var response = new CountingResponse();
        var queryFuture = new CompletableFuture<CountingResponse>();
        var discarded = new AtomicInteger();

        var subscription = Mono.fromFuture(() -> queryFuture)
                .doOnDiscard(AutoCloseable.class, ignored -> discarded.incrementAndGet())
                .subscribe();
        subscription.dispose();

        assertThat(queryFuture.isCancelled()).isTrue();
        // complete() is refused by an already-cancelled future, so the response is unreachable.
        assertThat(queryFuture.complete(response)).isFalse();
        assertThat(discarded).hasValue(0);
        assertThat(response.closeCount).hasValue(0);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
