package com.comet.opik.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link LockMetrics}. Covers the pure label-normalization logic (UUID
 * collapsing, null handling) directly, and asserts the best-effort recording contract:
 * a telemetry failure must never propagate to the caller. The recording methods run
 * against the no-op {@code GlobalOpenTelemetry} meter (no SDK registered in unit tests),
 * which is enough to exercise the {@code adjust()} delta state, the negative-wait clamp,
 * and the {@code record()} swallow-and-log guard without standing up an OTel pipeline.
 */
class LockMetricsTest {

    static Stream<Arguments> labelCollapsesUuids() {
        return Stream.of(
                // No UUID present — returned unchanged.
                Arguments.of("trace-threads-process", "trace-threads-process"),
                // Empty key — no UUID to collapse, returned as-is.
                Arguments.of("", ""),
                // Single leading UUID — the high-cardinality part is collapsed to '*'.
                Arguments.of("123e4567-e89b-12d3-a456-426614174000-trace-threads-process",
                        "*-trace-threads-process"),
                // Uppercase hex is still a valid UUID per the pattern.
                Arguments.of("123E4567-E89B-12D3-A456-426614174000-trace-threads-process",
                        "*-trace-threads-process"),
                // Multiple UUIDs — every occurrence is collapsed.
                Arguments.of("123e4567-e89b-12d3-a456-426614174000-x-223e4567-e89b-12d3-a456-426614174001",
                        "*-x-*"));
    }

    @ParameterizedTest(name = "label(''{0}'') -> ''{1}''")
    @MethodSource
    void labelCollapsesUuids(String key, String expected) {
        assertThat(LockMetrics.label(key)).isEqualTo(expected);
    }

    @Test
    void labelWhenKeyIsNullReturnsUnknown() {
        assertThat(LockMetrics.label(null)).isEqualTo("unknown");
    }

    @Test
    void recordingMetricsNeverThrows() {
        var metrics = new LockMetrics();
        var lock = "123e4567-e89b-12d3-a456-426614174000-trace-threads-process";

        // A full waiting -> held -> released lifecycle plus the terminal counters must
        // complete silently — telemetry is best-effort and must not break locking.
        assertThatCode(() -> {
            metrics.waitStart(lock);
            metrics.acquired(lock, 42);
            metrics.waitEnd(lock);
            metrics.heldStart(lock);
            metrics.heldEnd(lock);
            metrics.acquireFailed(lock);
        }).doesNotThrowAnyException();
    }

    @Test
    void adjustReturningToZeroNeverThrows() {
        var metrics = new LockMetrics();
        var lock = "123e4567-e89b-12d3-a456-426614174000-trace-threads-process";

        // Balanced increments/decrements drive the per-lock AtomicLong delta back to zero.
        assertThatCode(() -> {
            metrics.heldStart(lock);
            metrics.heldStart(lock);
            metrics.heldEnd(lock);
            metrics.heldEnd(lock);
        }).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "acquired(waitMillis={0}) is clamped and never throws")
    @ValueSource(longs = {-100L, 0L, 100L})
    void acquiredClampsNegativeWaitWithoutThrowing(long waitMillis) {
        var metrics = new LockMetrics();
        var lock = "123e4567-e89b-12d3-a456-426614174000-trace-threads-process";

        // Negative waits (clock skew) are clamped via Math.max(0, ...) before recording.
        assertThatCode(() -> metrics.acquired(lock, waitMillis)).doesNotThrowAnyException();
    }
}
