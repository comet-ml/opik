package com.comet.opik.infrastructure.redis;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.extern.slf4j.Slf4j;

/**
 * Observability for the distributed {@link RedissonLockService}. The signal that matters for OPIK-6813
 * is {@code lock_waiting}: callers blocked on a permit are {@code CompletableFuture}s held in the pod
 * heap (each pinning its payload), so a runaway acquire-queue is invisible in Redis and surfaces only
 * as heap growth. Tagged by the lock's stable {@code metricName} (see
 * {@link com.comet.opik.infrastructure.lock.LockService.Lock}) so cardinality is bounded by lock type,
 * not by entity.
 *
 * <p>Stateless: {@code lock_waiting}/{@code lock_held} are {@link LongUpDownCounter}s (OpenTelemetry keeps
 * the running per-name total). Acquire outcomes are a single multidimensional histogram,
 * {@code lock_acquire_wait_milliseconds}, tagged by {@code outcome=success|failure} — its {@code _count}
 * already gives the acquire/failure totals, so no separate counters are needed. Best-effort: a telemetry
 * failure must never break locking.
 */
@Slf4j
class LockMetrics {

    private static final AttributeKey<String> LOCK = AttributeKey.stringKey("lock");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final String SUCCESS = "success";
    private static final String FAILURE = "failure";

    private final LongUpDownCounter waiting;
    private final LongUpDownCounter held;
    private final LongHistogram acquireWait;

    LockMetrics() {
        Meter meter = GlobalOpenTelemetry.getMeter("opik.lock");
        this.waiting = meter.upDownCounterBuilder("lock_waiting")
                .setDescription("Acquire attempts currently waiting for a permit (queued in the pod heap)")
                .build();
        this.held = meter.upDownCounterBuilder("lock_held")
                .setDescription("Permits currently held (action running under the lock)")
                .build();
        this.acquireWait = meter.histogramBuilder("lock_acquire_wait_milliseconds")
                .setDescription("Time spent trying to acquire a permit; _count by outcome gives acquire/failure totals")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    void waitStart(String lock) {
        record(() -> waiting.add(1, lockAttrs(lock)));
    }

    void waitEnd(String lock) {
        record(() -> waiting.add(-1, lockAttrs(lock)));
    }

    void heldStart(String lock) {
        record(() -> held.add(1, lockAttrs(lock)));
    }

    void heldEnd(String lock) {
        record(() -> held.add(-1, lockAttrs(lock)));
    }

    void acquired(String lock, long waitMillis) {
        record(() -> acquireWait.record(Math.max(0, waitMillis), outcomeAttrs(lock, SUCCESS)));
    }

    void acquireFailed(String lock, long waitMillis) {
        record(() -> acquireWait.record(Math.max(0, waitMillis), outcomeAttrs(lock, FAILURE)));
    }

    private static Attributes lockAttrs(String lock) {
        return Attributes.of(LOCK, lock);
    }

    private static Attributes outcomeAttrs(String lock, String outcome) {
        return Attributes.of(LOCK, lock, OUTCOME, outcome);
    }

    private void record(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.debug("Failed to record lock metric", exception);
        }
    }
}
