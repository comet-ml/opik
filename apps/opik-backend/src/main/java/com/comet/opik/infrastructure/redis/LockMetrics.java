package com.comet.opik.infrastructure.redis;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Observability for the distributed {@link RedissonLockService}. The signal that matters for OPIK-6813
 * is {@code lock_waiting}: callers blocked on a permit are {@code CompletableFuture}s held in the pod
 * heap (each pinning its payload), so a runaway acquire-queue is invisible in Redis and surfaces only
 * as heap growth. These gauges/counters expose it directly, tagged by a low-cardinality lock name so a
 * per-project hotspot (e.g. {@code *-trace-threads-process}) is visible in Grafana without per-entity
 * cardinality. Best-effort: a telemetry failure must never break locking.
 */
@Slf4j
class LockMetrics {

    // UUID ids in the key are the high-cardinality part (projectId, datasetId, …); collapse them so the
    // label is the stable lock type, e.g. "{projectId}-trace-threads-process" -> "*-trace-threads-process".
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final AttributeKey<String> LOCK = AttributeKey.stringKey("lock");

    private final DoubleGauge waiting;
    private final DoubleGauge held;
    private final LongHistogram acquireWait;
    private final LongCounter acquired;
    private final LongCounter acquireFailed;

    private final ConcurrentHashMap<String, AtomicLong> waitingByLock = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> heldByLock = new ConcurrentHashMap<>();

    LockMetrics() {
        Meter meter = GlobalOpenTelemetry.getMeter("opik.lock");
        this.waiting = meter.gaugeBuilder("lock_waiting")
                .setDescription("Acquire attempts currently waiting for a permit (queued in the pod heap)")
                .build();
        this.held = meter.gaugeBuilder("lock_held")
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

    static String label(String key) {
        return key == null ? "unknown" : UUID.matcher(key).replaceAll("*");
    }

    void waitStart(String lock) {
        adjust(waiting, waitingByLock, lock, 1);
    }

    void waitEnd(String lock) {
        adjust(waiting, waitingByLock, lock, -1);
    }

    void heldStart(String lock) {
        adjust(held, heldByLock, lock, 1);
    }

    void heldEnd(String lock) {
        adjust(held, heldByLock, lock, -1);
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

    private void adjust(DoubleGauge gauge, ConcurrentHashMap<String, AtomicLong> state, String lock, long delta) {
        record(() -> gauge.set(state.computeIfAbsent(lock, k -> new AtomicLong()).addAndGet(delta), attrs(lock)));
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
