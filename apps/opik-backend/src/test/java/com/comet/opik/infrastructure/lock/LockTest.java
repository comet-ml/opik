package com.comet.opik.infrastructure.lock;

import com.comet.opik.infrastructure.lock.LockService.Lock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Lock#metricName()} — the low-cardinality label exposed on the lock for metrics.
 * It collapses embedded UUIDs to {@code *} so cardinality is bounded by lock type, not by entity. The
 * Redis {@code key} itself is left untouched (high-cardinality, the lock's real identity).
 */
class LockTest {

    static Stream<Arguments> metricNameCollapsesUuids() {
        return Stream.of(
                // Static key, no UUID — unchanged.
                Arguments.of(new Lock("alert_job:scan_lock"), "alert_job:scan_lock"),
                // UUID-scoped lock: id collapses, stable name kept.
                Arguments.of(new Lock(UUID.fromString("0190babc-62a0-71d2-832a-0feffa4676eb"), "Trace"), "*-Trace"),
                // Pre-formatted dynamic key: embedded UUID collapses, surrounding template kept.
                Arguments.of(new Lock("prompt_version:0190babc-62a0-71d2-832a-0feffa4676eb:c12d"),
                        "prompt_version:*:c12d"),
                // Multiple UUIDs all collapse.
                Arguments.of(new Lock("0190babc-62a0-71d2-832a-0feffa4676eb-0190babc-62a0-71d2-832a-0feffa4676ec"),
                        "*-*"));
    }

    @ParameterizedTest(name = "metricName -> ''{1}''")
    @MethodSource
    void metricNameCollapsesUuids(Lock lock, String expected) {
        assertThat(lock.metricName()).isEqualTo(expected);
    }

    @Test
    void metricNameLeavesTheRedisKeyUntouched() {
        var lock = new Lock(UUID.fromString("0190babc-62a0-71d2-832a-0feffa4676eb"), "Trace");

        // The Redis identity keeps the full UUID; only the metric label collapses it.
        assertThat(lock.key()).isEqualTo("0190babc-62a0-71d2-832a-0feffa4676eb-Trace");
        assertThat(lock.metricName()).isEqualTo("*-Trace");
    }
}
