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
 * flagging while a flush is pending share it, and the next flush starts no sooner than the interval after the
 * previous one started, so a request waits at most about one interval plus the flush.
 */
class FreeFormSqlQueryLogFlusher {

    private final Supplier<CompletableFuture<Void>> flush;
    private final long minIntervalMillis;
    private final LongSupplier clock;

    private long lastStartMillis = Long.MIN_VALUE / 2;
    private CompletableFuture<Void> pending;

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

    private void start(CompletableFuture<Void> done) {
        synchronized (this) {
            lastStartMillis = clock.getAsLong();
            pending = null;
        }
        try {
            flush.get().whenComplete((ok, error) -> {
                if (error != null) {
                    done.completeExceptionally(error);
                } else {
                    done.complete(null);
                }
            });
        } catch (RuntimeException e) {
            done.completeExceptionally(e);
        }
    }
}
