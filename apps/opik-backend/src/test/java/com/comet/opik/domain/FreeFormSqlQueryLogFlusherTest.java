package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Free-form SQL query log flusher")
class FreeFormSqlQueryLogFlusherTest {

    private static final long INTERVAL_MILLIS = 300;

    private final List<Long> flushStarts = new CopyOnWriteArrayList<>();
    private final AtomicReference<CompletableFuture<Void>> running = new AtomicReference<>();

    /** A flush that records when it started and finishes only when the test says so. */
    private FreeFormSqlQueryLogFlusher flusher() {
        return new FreeFormSqlQueryLogFlusher(() -> {
            // Published before the start is recorded, so a test that sees the start also sees the flush.
            var flush = new CompletableFuture<Void>();
            running.set(flush);
            flushStarts.add(System.currentTimeMillis());
            return flush;
        }, INTERVAL_MILLIS, System::currentTimeMillis);
    }

    @Test
    @DisplayName("requests waiting at the same time share one flush")
    void waitersShareOneFlush() {
        var flusher = flusher();
        var waiters = IntStream.range(0, 20).mapToObj(i -> flusher.awaitFlush()).toList();

        waitUntil(() -> flushStarts.size() == 1);
        running.get().complete(null);

        CompletableFuture.allOf(waiters.toArray(CompletableFuture[]::new)).join();
        assertThat(flushStarts).hasSize(1);
    }

    @Test
    @DisplayName("a request flagged while a flush runs gets the next one, at least an interval later")
    void flushesAreAtLeastAnIntervalApart() {
        var flusher = flusher();
        var first = flusher.awaitFlush();
        waitUntil(() -> flushStarts.size() == 1);

        // Its query may have finished after the running flush started, so that flush cannot cover it.
        var second = flusher.awaitFlush();
        running.get().complete(null);
        first.join();
        assertThat(second).isNotDone();

        waitUntil(() -> flushStarts.size() == 2);
        running.get().complete(null);
        second.join();
        assertThat(flushStarts.get(1) - flushStarts.get(0)).isGreaterThanOrEqualTo(INTERVAL_MILLIS);
    }

    @Test
    @DisplayName("a failed flush fails its waiters, and the next request gets a new flush")
    void failedFlushFailsItsWaiters() {
        var flusher = flusher();
        var waiter = flusher.awaitFlush();
        waitUntil(() -> flushStarts.size() == 1);
        running.get().completeExceptionally(new IllegalStateException("flush timed out"));

        assertThatThrownBy(waiter::join).hasCauseInstanceOf(IllegalStateException.class);
        var next = flusher.awaitFlush();
        waitUntil(() -> flushStarts.size() == 2);
        running.get().complete(null);
        next.join();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("condition within 5s").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("a flush slower than the interval is never overlapped: the next starts after it finishes")
    void slowFlushIsNeverOverlapped() throws InterruptedException {
        var flusher = flusher();
        var first = flusher.awaitFlush();
        waitUntil(() -> flushStarts.size() == 1);
        var slow = running.get();

        var second = flusher.awaitFlush();
        Thread.sleep(3 * INTERVAL_MILLIS);
        assertThat(flushStarts).as("no second flush while the first runs").hasSize(1);
        // Still pending, so a request flagged now shares it.
        assertThat(flusher.awaitFlush()).isSameAs(second);

        slow.complete(null);
        first.join();
        waitUntil(() -> flushStarts.size() == 2);
        running.get().complete(null);
        second.join();
    }
}
