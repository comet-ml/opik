package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic: time is a manual clock, and scheduled tasks run only when the test advances it past them. */
@DisplayName("Free-form SQL query log flusher")
class FreeFormSqlQueryLogFlusherTest {

    private static final long INTERVAL_MILLIS = 1_000;
    private static final long GATE_RETRY_MILLIS = 200;

    /** Whether the cluster-wide permit is granted: false while another instance has just flushed. */
    private boolean gateOpen = true;

    private long now;
    private final List<Task> scheduled = new ArrayList<>();
    private final List<Long> flushStarts = new ArrayList<>();
    private final List<CompletableFuture<Void>> flushes = new ArrayList<>();

    private record Task(long dueMillis, Runnable run) {
    }

    /** Each flush records when it started and finishes only when the test completes it. */
    private final FreeFormSqlQueryLogFlusher flusher = new FreeFormSqlQueryLogFlusher(() -> {
        flushStarts.add(now);
        var flush = new CompletableFuture<Void>();
        flushes.add(flush);
        return flush;
    }, INTERVAL_MILLIS, GATE_RETRY_MILLIS, () -> now,
            (delayMillis, task) -> scheduled.add(new Task(now + delayMillis, task)),
            () -> CompletableFuture.completedFuture(gateOpen));

    /** Moves the clock to {@code millis}, running every task due by then, in due order. */
    private void advanceTo(long millis) {
        now = millis;
        while (true) {
            var due = scheduled.stream().filter(task -> task.dueMillis() <= now)
                    .min((a, b) -> Long.compare(a.dueMillis(), b.dueMillis())).orElse(null);
            if (due == null) {
                return;
            }
            scheduled.remove(due);
            due.run().run();
        }
    }

    @Test
    @DisplayName("requests waiting at the same time share one flush")
    void waitersShareOneFlush() {
        var waiters = IntStream.range(0, 20).mapToObj(i -> flusher.awaitFlush()).toList();
        advanceTo(0);
        assertThat(flushStarts).containsExactly(0L);

        flushes.getFirst().complete(null);
        assertThat(waiters).allMatch(CompletableFuture::isDone);
    }

    @Test
    @DisplayName("a request flagged while a flush runs gets the next one, an interval after the previous start")
    void flushesAreAtLeastAnIntervalApart() {
        var first = flusher.awaitFlush();
        advanceTo(0);
        // Its query may have finished after the running flush started, so that flush cannot cover it.
        var second = flusher.awaitFlush();
        flushes.getFirst().complete(null);
        assertThat(first).isDone();
        assertThat(second).isNotDone();

        advanceTo(INTERVAL_MILLIS - 1);
        assertThat(flushStarts).as("not before the interval").containsExactly(0L);
        advanceTo(INTERVAL_MILLIS);
        assertThat(flushStarts).containsExactly(0L, INTERVAL_MILLIS);
        flushes.getLast().complete(null);
        assertThat(second).isDone();
    }

    @Test
    @DisplayName("a flush slower than the interval is never overlapped, and its successor stays shared until it starts")
    void slowFlushIsNeverOverlapped() {
        var first = flusher.awaitFlush();
        advanceTo(0);
        var second = flusher.awaitFlush();

        advanceTo(3 * INTERVAL_MILLIS);
        assertThat(flushStarts).as("no second flush while the first runs").containsExactly(0L);
        assertThat(flusher.awaitFlush()).as("still pending, so shared").isSameAs(second);

        flushes.getFirst().complete(null);
        assertThat(first).isDone();
        assertThat(flushStarts).as("starts as soon as the first finishes").containsExactly(0L, 3 * INTERVAL_MILLIS);
        flushes.getLast().complete(null);
        assertThat(second).isDone();
    }

    @Test
    @DisplayName("a failed flush fails its waiters, and the next request gets a new flush")
    void failedFlushFailsItsWaiters() {
        var waiter = flusher.awaitFlush();
        advanceTo(0);
        flushes.getFirst().completeExceptionally(new IllegalStateException("flush timed out"));
        assertThatThrownBy(waiter::join).hasCauseInstanceOf(IllegalStateException.class);

        var next = flusher.awaitFlush();
        advanceTo(INTERVAL_MILLIS);
        assertThat(flushStarts).containsExactly(0L, INTERVAL_MILLIS);
        flushes.getLast().complete(null);
        assertThat(next).isDone();
    }

    @Test
    @DisplayName("without the cluster-wide permit no flush starts; it asks again and starts once granted")
    void deniedPermitDefersTheFlush() {
        gateOpen = false;
        var waiter = flusher.awaitFlush();
        advanceTo(0);
        advanceTo(5 * GATE_RETRY_MILLIS);
        assertThat(flushStarts).as("another instance holds this interval").isEmpty();
        assertThat(flusher.awaitFlush()).as("still pending, so shared").isSameAs(waiter);

        gateOpen = true;
        advanceTo(6 * GATE_RETRY_MILLIS);
        assertThat(flushStarts).containsExactly(6 * GATE_RETRY_MILLIS);
        flushes.getFirst().complete(null);
        assertThat(waiter).isDone();
    }
}
