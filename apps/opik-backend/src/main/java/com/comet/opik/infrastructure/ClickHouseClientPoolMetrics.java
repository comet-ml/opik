package com.comet.opik.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

import java.util.Optional;

/**
 * Publishes the ClickHouse v2 client's connection pool as OpenTelemetry gauges, to tell when
 * {@code clientMaxOpenConnections} is too small: {@code opik.clickhouse.client.pool.pending} above zero means requests
 * are queued for a connection, and when it stays above zero, raising the pool size helps, as long as ClickHouse insert
 * and read latency still have room.
 *
 * <p>client-v2 publishes its pool only through a Micrometer registry, so the client binds its gauges to a private one
 * here (see {@link DatabaseAnalyticsFactory#buildClient(ClickHouseClientPoolMetrics)}) and each OpenTelemetry callback
 * reads the current values from it. Nothing is recorded if the client failed to bind them, which it logs.
 */
public final class ClickHouseClientPoolMetrics {

    /**
     * Micrometer tag value client-v2 requires for its gauges; unused, since the registry holds only this one client.
     */
    static final String GROUP_NAME = "opik";

    private static final String TOTAL_MAX = "httpcomponents.httpclient.pool.total.max";
    private static final String TOTAL_CONNECTIONS = "httpcomponents.httpclient.pool.total.connections";
    private static final String TOTAL_PENDING = "httpcomponents.httpclient.pool.total.pending";
    private static final String STATE_TAG = "state";
    private static final String LEASED = "leased";
    private static final String AVAILABLE = "available";
    private static final AttributeKey<String> STATE = AttributeKey.stringKey(STATE_TAG);

    @Getter(AccessLevel.PACKAGE)
    private final MeterRegistry registry = new SimpleMeterRegistry();

    public ClickHouseClientPoolMetrics() {
        Meter meter = GlobalOpenTelemetry.getMeter("opik.clickhouse.client");
        meter.gaugeBuilder("opik.clickhouse.client.pool.max").ofLongs()
                .setDescription("Configured size of the ClickHouse v2 client's connection pool")
                .buildWithCallback(measurement -> stats().ifPresent(stats -> measurement.record(stats.max())));
        meter.gaugeBuilder("opik.clickhouse.client.pool.connections").ofLongs()
                .setDescription("Pooled connections by state: leased (running a request) or available (idle)")
                .buildWithCallback(measurement -> stats().ifPresent(stats -> {
                    measurement.record(stats.leased(), Attributes.of(STATE, LEASED));
                    measurement.record(stats.available(), Attributes.of(STATE, AVAILABLE));
                }));
        meter.gaugeBuilder("opik.clickhouse.client.pool.pending").ofLongs()
                .setDescription("Requests waiting for a free connection in the ClickHouse v2 client's pool")
                .buildWithCallback(measurement -> stats().ifPresent(stats -> measurement.record(stats.pending())));
    }

    /**
     * The pool's current state, or empty until a client has bound its gauges to this registry.
     */
    Optional<PoolStats> stats() {
        var max = registry.find(TOTAL_MAX).gauge();
        if (max == null) {
            return Optional.empty();
        }
        return Optional.of(PoolStats.builder()
                .max((long) max.value())
                .leased(value(registry.find(TOTAL_CONNECTIONS).tag(STATE_TAG, LEASED).gauge()))
                .available(value(registry.find(TOTAL_CONNECTIONS).tag(STATE_TAG, AVAILABLE).gauge()))
                .pending(value(registry.find(TOTAL_PENDING).gauge()))
                .build());
    }

    private static long value(Gauge gauge) {
        return gauge == null ? 0 : (long) gauge.value();
    }

    @Builder(toBuilder = true)
    record PoolStats(long max, long leased, long available, long pending) {
    }
}
