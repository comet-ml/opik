package com.comet.opik.infrastructure.http;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.pool.PoolStats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the shared Jersey client's connection pool as OpenTelemetry gauges: the pool's totals, and the share held
 * by the route to opik-inference, which guardrail checks, ai-labels and Topics all call through this client.
 * Dropwizard registers the totals in its MetricRegistry, but nothing exports that registry, and it has no per-route
 * view. {@code opik.http.client.pool.pending} above zero on the opik-inference route means those calls wait for a
 * connection: the per-route limit is what {@code OpikConfiguration.isInferenceCallsWithinConnectionPool} sizes.
 * <p>
 * Only the opik-inference route is named: the pool also serves customer webhooks and LLM providers, whose hosts stay
 * out of the metrics.
 */
@UtilityClass
public class HttpClientPoolMetrics {

    static final String ALL_ROUTES = "all";
    static final String INFERENCE_ROUTE = "opik-inference";
    static final String LEASED = "leased";
    static final String AVAILABLE = "available";
    static final AttributeKey<String> ROUTE = AttributeKey.stringKey("route");
    static final AttributeKey<String> STATE = AttributeKey.stringKey("state");

    /**
     * @param inferenceHost the host of {@code inference.url}, or null when no feature is set to call opik-inference
     */
    public static void publish(@NonNull PoolingHttpClientConnectionManager pool, String inferenceHost) {
        publish(GlobalOpenTelemetry.getMeter("opik.http.client"), pool, inferenceHost);
    }

    static void publish(@NonNull Meter meter, @NonNull PoolingHttpClientConnectionManager pool, String inferenceHost) {
        meter.gaugeBuilder("opik.http.client.pool.max").ofLongs()
                .setDescription("Connections the shared HTTP client's pool may hold: in all, and on the route to "
                        + "opik-inference")
                .buildWithCallback(measurement -> stats(pool, inferenceHost).forEach(
                        (route, stats) -> measurement.record(stats.getMax(), Attributes.of(ROUTE, route))));
        meter.gaugeBuilder("opik.http.client.pool.connections").ofLongs()
                .setDescription("Pooled connections by state, leased (running a request) or available (idle): in all, "
                        + "and on the route to opik-inference")
                .buildWithCallback(measurement -> stats(pool, inferenceHost).forEach((route, stats) -> {
                    measurement.record(stats.getLeased(), Attributes.of(ROUTE, route, STATE, LEASED));
                    measurement.record(stats.getAvailable(), Attributes.of(ROUTE, route, STATE, AVAILABLE));
                }));
        meter.gaugeBuilder("opik.http.client.pool.pending").ofLongs()
                .setDescription("Requests waiting for a free connection in the shared HTTP client's pool: in all, and "
                        + "on the route to opik-inference")
                .buildWithCallback(measurement -> stats(pool, inferenceHost).forEach(
                        (route, stats) -> measurement.record(stats.getPending(), Attributes.of(ROUTE, route))));
    }

    /**
     * The pool's totals and, when a host is known, the opik-inference route's: nothing in use until the pool has seen
     * it, with the per-route limit as its max.
     */
    static Map<String, PoolStats> stats(PoolingHttpClientConnectionManager pool, String inferenceHost) {
        var stats = new LinkedHashMap<String, PoolStats>();
        stats.put(ALL_ROUTES, pool.getTotalStats());
        if (StringUtils.isNotBlank(inferenceHost)) {
            stats.put(INFERENCE_ROUTE, pool.getRoutes().stream()
                    .filter(route -> route.getTargetHost().getHostName().equalsIgnoreCase(inferenceHost))
                    .map(pool::getStats)
                    .reduce(new PoolStats(0, 0, 0, pool.getDefaultMaxPerRoute()), HttpClientPoolMetrics::sum));
        }
        return stats;
    }

    /** Routes to one host differ only in scheme or port, so they share the per-route limit. */
    private static PoolStats sum(PoolStats first, PoolStats second) {
        return new PoolStats(first.getLeased() + second.getLeased(), first.getPending() + second.getPending(),
                first.getAvailable() + second.getAvailable(), Math.max(first.getMax(), second.getMax()));
    }
}
