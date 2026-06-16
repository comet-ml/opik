package com.comet.opik.infrastructure.redis;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // Safety bound on distinct lock labels we will record. Keys are UUID-collapsed before reaching here
    // (see RedissonLockService), so the labeled set equals the fixed number of lock templates in the code
    // (~25 today). The cap is a guard: if a future caller interpolates a non-UUID dynamic segment (a name,
    // an email, …) into a lock key, the "lock" attribute would otherwise grow without bound and blow up
    // metric cardinality. Past the cap we skip the new label and log once, instead of leaking series.
    static final int MAX_DISTINCT_LOCKS = 256;

    private final DoubleGauge waiting;
    private final DoubleGauge held;
    private final LongHistogram acquireWait;
    private final LongCounter acquired;
    private final LongCounter acquireFailed;

    private final ConcurrentHashMap<String, AtomicLong> waitingByLock = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> heldByLock = new ConcurrentHashMap<>();

    // Distinct lock labels admitted so far — bounds both the gauge maps and the "lock" attribute cardinality.
    private final Set<String> admittedLocks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cardinalityCapLogged = new AtomicBoolean();

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
        record(lock, () -> adjust(waiting, waitingByLock, lock, 1));
    }

    void waitEnd(String lock) {
        record(lock, () -> adjust(waiting, waitingByLock, lock, -1));
    }

    void heldStart(String lock) {
        record(lock, () -> adjust(held, heldByLock, lock, 1));
    }

    void heldEnd(String lock) {
        record(lock, () -> adjust(held, heldByLock, lock, -1));
    }

    void acquired(String lock, long waitMillis) {
        record(lock, () -> {
            acquireWait.record(Math.max(0, waitMillis), attrs(lock));
            acquired.add(1, attrs(lock));
        });
    }

    void acquireFailed(String lock) {
        record(lock, () -> acquireFailed.add(1, attrs(lock)));
    }

    private void adjust(DoubleGauge gauge, ConcurrentHashMap<String, AtomicLong> state, String lock, long delta) {
        gauge.set(state.computeIfAbsent(lock, k -> new AtomicLong()).addAndGet(delta), attrs(lock));
    }

    private static Attributes attrs(String lock) {
        return Attributes.of(LOCK, lock);
    }

    /**
     * Records the metric only for an admitted lock label, swallowing any telemetry failure — metrics are
     * best-effort and must never break locking. A label beyond {@link #MAX_DISTINCT_LOCKS} is skipped and
     * logged, so a caller that interpolates a non-UUID dynamic segment into a lock key can't explode the
     * metric's cardinality. {@code admit} runs inside the guard, so even an unexpected null label is logged
     * and skipped rather than propagated.
     */
    private void record(String lock, Runnable action) {
        try {
            if (admit(lock)) {
                action.run();
            }
        } catch (RuntimeException exception) {
            log.debug("Failed to record lock metric", exception);
        }
    }

    private boolean admit(String lock) {
        if (admittedLocks.contains(lock)) {
            return true;
        }
        if (admittedLocks.size() >= MAX_DISTINCT_LOCKS) {
            if (cardinalityCapLogged.compareAndSet(false, true)) {
                log.error("Lock metric cardinality cap '{}' reached; skipping new lock labels."
                        + " A lock key likely contains a non-UUID dynamic segment.", MAX_DISTINCT_LOCKS);
            }
            return false;
        }
        admittedLocks.add(lock);
        return true;
    }

    // Distinct lock labels currently tracked; package-private for tests / cardinality observability.
    int trackedLocks() {
        return admittedLocks.size();
    }
}
