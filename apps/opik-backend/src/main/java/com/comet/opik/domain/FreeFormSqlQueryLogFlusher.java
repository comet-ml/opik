package com.comet.opik.domain;

import lombok.NonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The one place that flushes {@code system.query_log} for the post-run check, at most once per
 * {@code minIntervalMillis}. A request never flushes: it flags that it is waiting, after its query finished, and gets
 * a future that completes when a flush that started after the flag is done; then it only queries the log. Requests
 * flagging while a flush is pending share it. The next flush starts no sooner than the interval after the previous
 * one started, and never while one is still running, so flushes never overlap and a request waits at most about one
 * interval plus the flushes ahead of it.
 */
class FreeFormSqlQueryLogFlusher {

    private final Supplier<CompletableFuture<Void>> flush;
    private final long minIntervalMillis;
    private final LongSupplier clock;

    private long lastStartMillis = Long.MIN_VALUE / 2;
    /** The flush the next waiters will get; registered until it starts, so later requests share it. */
    private CompletableFuture<Void> pending;
    /** The flush running now, if any: the next one never starts before it finishes. */
    private CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);

    FreeFormSqlQueryLogFlusher(@NonNull Supplier<CompletableFuture<Void>> flush, long minIntervalMillis,
            @NonNull LongSupplier clock) {
        this.flush = flush;
        this.minIntervalMillis = minIntervalMillis;
        this.clock = clock;
    }

    /** Flags a waiting request. @return completes once a flush that started after this call has finished */
    synchronized CompletableFuture<Void> awaitFlush() {
        if (pending != null) {
            return pending;
        }
        var done = new CompletableFuture<Void>();
        pending = done;
        long delay = Math.max(0, lastStartMillis + minIntervalMillis - clock.getAsLong());
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> start(done));
        return done;
    }

    /** Starts {@code done}'s flush once none is running; until then it stays pending for new waiters. */
    private void start(CompletableFuture<Void> done) {
        CompletableFuture<Void> running;
        synchronized (this) {
            if (!inFlight.isDone()) {
                inFlight.whenComplete((ok, error) -> start(done));
                return;
            }
            lastStartMillis = clock.getAsLong();
            pending = null;
            running = new CompletableFuture<>();
            inFlight = running;
        }
        CompletableFuture<Void> flushed;
        try {
            flushed = flush.get();
        } catch (RuntimeException e) {
            flushed = CompletableFuture.failedFuture(e);
        }
        flushed.whenComplete((ok, error) -> {
            running.complete(null);
            if (error != null) {
                done.completeExceptionally(error);
            } else {
                done.complete(null);
            }
        });
    }
}
