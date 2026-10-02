package com.comet.opik.domain;

import lombok.NonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The one place that flushes {@code system.query_log} for the post-run check, at most once per
 * {@code minIntervalMillis}: the worst case, since a request whose entries are already written never asks
 * ({@link FreeFormSqlQueryLogReader}). A request never flushes itself: it flags that it is waiting, after its query
 * finished, and gets a future that completes when a flush that started after the flag is done; then it queries again. Requests
 * flagging while a flush is pending share it. The next flush starts no sooner than the interval after the previous
 * one started, and never while one is still running, so flushes never overlap and a request waits at most about one
 * interval plus the flushes ahead of it.
 *
 * <p>The limit is cluster-wide: before each flush the flusher takes a permit from a {@link ClusterGate}, one per
 * interval across every backend instance. Without one, another instance flushed less than an interval ago, so it
 * tries again shortly; its waiters stay pending meanwhile.
 */
class FreeFormSqlQueryLogFlusher {

    /** Runs {@code task} after {@code delayMillis}; injected so tests control time. */
    interface Scheduler {
        void schedule(long delayMillis, Runnable task);

        Scheduler DELAYED = (delayMillis, task) -> CompletableFuture
                .delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(task);
    }

    /** One flush permit per interval across all backend instances: true if this instance may flush now. */
    interface ClusterGate {
        CompletableFuture<Boolean> tryAcquire();
    }

    private final Supplier<CompletableFuture<Void>> flush;
    private final long minIntervalMillis;
    private final long gateRetryMillis;
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final ClusterGate gate;

    private long lastStartMillis = Long.MIN_VALUE / 2;
    /** The flush the next waiters will get; registered until it starts, so later requests share it. */
    private CompletableFuture<Void> pending;
    /** The flush running now, if any: the next one never starts before it finishes. */
    private CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);

    FreeFormSqlQueryLogFlusher(@NonNull Supplier<CompletableFuture<Void>> flush, long minIntervalMillis,
            long gateRetryMillis, @NonNull LongSupplier clock, @NonNull Scheduler scheduler,
            @NonNull ClusterGate gate) {
        this.flush = flush;
        this.minIntervalMillis = minIntervalMillis;
        this.gateRetryMillis = gateRetryMillis;
        this.clock = clock;
        this.scheduler = scheduler;
        this.gate = gate;
    }

    /** Flags a waiting request. @return completes once a flush that started after this call has finished */
    synchronized CompletableFuture<Void> awaitFlush() {
        if (pending != null) {
            return pending;
        }
        var done = new CompletableFuture<Void>();
        pending = done;
        long delay = Math.max(0, lastStartMillis + minIntervalMillis - clock.getAsLong());
        scheduler.schedule(delay, () -> start(done));
        return done;
    }

    /** Starts {@code done}'s flush once none is running here and the cluster grants a permit; until then it stays pending. */
    private void start(CompletableFuture<Void> done) {
        synchronized (this) {
            if (!inFlight.isDone()) {
                inFlight.whenComplete((ok, error) -> start(done));
                return;
            }
        }
        gate.tryAcquire().whenComplete((granted, error) -> {
            if (error != null || !Boolean.TRUE.equals(granted)) {
                scheduler.schedule(gateRetryMillis, () -> start(done));
            } else {
                run(done);
            }
        });
    }

    private void run(CompletableFuture<Void> done) {
        CompletableFuture<Void> running;
        synchronized (this) {
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
