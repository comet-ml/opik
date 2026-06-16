package com.comet.opik.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link LockMetrics}. The class is stateless — {@code lock_waiting}/{@code lock_held} are
 * OpenTelemetry UpDownCounters, so there's nothing to assert about internal counters here. These tests pin
 * the best-effort contract: recording runs against the no-op {@code GlobalOpenTelemetry} meter (no SDK in
 * unit tests) and must never throw, including the negative-wait clamp and a balanced increment/decrement
 * lifecycle. The low-cardinality label itself lives on {@code LockService.Lock} (see {@code LockTest}).
 */
class LockMetricsTest {

    private static final String LOCK = "trace-threads-process";

    @Test
    void recordingMetricsNeverThrows() {
        var metrics = new LockMetrics();

        // A full waiting -> acquired -> held -> released lifecycle plus the failure counter must
        // complete silently — telemetry is best-effort and must not break locking.
        assertThatCode(() -> {
            metrics.waitStart(LOCK);
            metrics.acquired(LOCK, 42);
            metrics.waitEnd(LOCK);
            metrics.heldStart(LOCK);
            metrics.heldEnd(LOCK);
            metrics.acquireFailed(LOCK);
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "acquired(waitMillis={0}) is clamped and never throws")
    @ValueSource(longs = {-100L, 0L, 100L})
    void acquiredClampsNegativeWaitWithoutThrowing(long waitMillis) {
        var metrics = new LockMetrics();

        // Negative waits (clock skew) are clamped via Math.max(0, ...) before recording.
        assertThatCode(() -> metrics.acquired(LOCK, waitMillis)).doesNotThrowAnyException();
    }
}
