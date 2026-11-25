package com.comet.opik.infrastructure.http;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.pool.PoolStats;

import java.net.URI;
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

    static final String MAX = "opik.http.client.pool.max";
    static final String CONNECTIONS = "opik.http.client.pool.connections";
    static final String PENDING = "opik.http.client.pool.pending";
    static final String ALL_ROUTES = "all";
    static final String INFERENCE_ROUTE = "opik-inference";
    static final String LEASED = "leased";
    static final String AVAILABLE = "available";
    static final AttributeKey<String> ROUTE = AttributeKey.stringKey("route");
    static final AttributeKey<String> STATE = AttributeKey.stringKey("state");

    /**
     * @param inferenceUrl {@code inference.url}, or blank when no feature is set to call opik-inference
     */
    public static void publish(@NonNull PoolingHttpClientConnectionManager pool, String inferenceUrl) {
        publish(GlobalOpenTelemetry.getMeter("opik.http.client"), pool, inferenceUrl);
    }

    static void publish(@NonNull Meter meter, @NonNull PoolingHttpClientConnectionManager pool, String inferenceUrl) {
        var inferenceTarget = target(inferenceUrl);
        meter.gaugeBuilder(MAX).ofLongs()
                .setDescription("Connections the shared HTTP client's pool may hold: in all, and on the route to "
                        + "opik-inference")
                .buildWithCallback(measurement -> stats(pool, inferenceTarget).forEach(
                        (route, stats) -> measurement.record(stats.getMax(), Attributes.of(ROUTE, route))));
        meter.gaugeBuilder(CONNECTIONS).ofLongs()
                .setDescription("Pooled connections by state, leased (running a request) or available (idle): in all, "
                        + "and on the route to opik-inference")
                .buildWithCallback(measurement -> stats(pool, inferenceTarget).forEach((route, stats) -> {
                    measurement.record(stats.getLeased(), Attributes.of(ROUTE, route, STATE, LEASED));
                    measurement.record(stats.getAvailable(), Attributes.of(ROUTE, route, STATE, AVAILABLE));
                }));
        meter.gaugeBuilder(PENDING).ofLongs()
                .setDescription("Requests waiting for a free connection in the shared HTTP client's pool: in all, and "
                        + "on the route to opik-inference")
                .buildWithCallback(measurement -> stats(pool, inferenceTarget).forEach(
                        (route, stats) -> measurement.record(stats.getPending(), Attributes.of(ROUTE, route))));
    }

    /**
     * The target of the pool's route to a URL: its scheme, host and port, with the scheme's default port filled in as
     * the client's route planner does, so it equals that route's target host. Null for a blank URL.
     */
    private static HttpHost target(String url) {
        return StringUtils.isBlank(url)
                ? null
                : RoutingSupport.normalize(HttpHost.create(URI.create(url)), DefaultSchemePortResolver.INSTANCE);
    }

    /**
     * The pool's totals and, when the opik-inference target is known, its route's: nothing in use until the pool has
     * seen it, with the per-route limit as its max. The pool limits each route on its own, and the client plans one
     * route per target, so the route is the one whose target matches in scheme, host and port.
     */
    static Map<String, PoolStats> stats(PoolingHttpClientConnectionManager pool, HttpHost inferenceTarget) {
        var stats = new LinkedHashMap<String, PoolStats>();
        stats.put(ALL_ROUTES, pool.getTotalStats());
        if (inferenceTarget != null) {
            stats.put(INFERENCE_ROUTE, pool.getRoutes().stream()
                    .filter(route -> inferenceTarget.equals(route.getTargetHost()))
                    .findFirst()
                    .map(pool::getStats)
                    .orElseGet(() -> new PoolStats(0, 0, 0, pool.getDefaultMaxPerRoute())));
        }
        return stats;
    }
}
