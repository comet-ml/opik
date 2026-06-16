package com.comet.opik.infrastructure.redis;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.extern.slf4j.Slf4j;

/**
 * Observability for the distributed {@link RedissonLockService}. The signal that matters for OPIK-6813
 * is {@code lock_waiting}: callers blocked on a permit are {@code CompletableFuture}s held in the pod
 * heap (each pinning its payload), so a runaway acquire-queue is invisible in Redis and surfaces only
 * as heap growth. These instruments expose it directly, tagged by the lock's stable {@code name} (see
 * {@link com.comet.opik.infrastructure.lock.LockService.Lock}) so cardinality is bounded by the set of
 * declared lock names, not by how many entities exist. Stateless: {@code lock_waiting}/{@code lock_held}
 * are {@link LongUpDownCounter}s, so OpenTelemetry keeps the running per-name total — we don't. Best-effort:
 * a telemetry failure must never break locking.
 */
@Slf4j
class LockMetrics {

    private static final AttributeKey<String> LOCK = AttributeKey.stringKey("lock");

    private final LongUpDownCounter waiting;
    private final LongUpDownCounter held;
    private final LongHistogram acquireWait;
    private final LongCounter acquired;
    private final LongCounter acquireFailed;

    LockMetrics() {
        Meter meter = GlobalOpenTelemetry.getMeter("opik.lock");
        this.waiting = meter.upDownCounterBuilder("lock_waiting")
                .setDescription("Acquire attempts currently waiting for a permit (queued in the pod heap)")
                .build();
        this.held = meter.upDownCounterBuilder("lock_held")
                .setDescription("Permits currently held (action running under the lock)")
                .build();
        this.acquireWait = meter.histogramBuilder("lock_acquire_wait_milliseconds")
                .setDescription("Time spent waiting to acquire a permit")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.acquired = meter.counterBuilder("lock_acquired_total")
                .setDescription("Permits successfully acquired")
                .build();
        this.acquireFailed = meter.counterBuilder("lock_acquire_failed_total")
                .setDescription("Acquire attempts that errored or failed to obtain a permit")
                .build();
    }

    void waitStart(String lock) {
        record(() -> waiting.add(1, attrs(lock)));
    }

    void waitEnd(String lock) {
        record(() -> waiting.add(-1, attrs(lock)));
    }

    void heldStart(String lock) {
        record(() -> held.add(1, attrs(lock)));
    }

    void heldEnd(String lock) {
        record(() -> held.add(-1, attrs(lock)));
    }

    void acquired(String lock, long waitMillis) {
        record(() -> {
            acquireWait.record(Math.max(0, waitMillis), attrs(lock));
            acquired.add(1, attrs(lock));
        });
    }

    void acquireFailed(String lock) {
        record(() -> acquireFailed.add(1, attrs(lock)));
    }

    private static Attributes attrs(String lock) {
        return Attributes.of(LOCK, lock);
    }

    private void record(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.debug("Failed to record lock metric", exception);
        }
    }
}
