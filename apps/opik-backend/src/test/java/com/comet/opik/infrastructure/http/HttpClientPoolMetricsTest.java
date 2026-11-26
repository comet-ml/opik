package com.comet.opik.infrastructure.http;

import com.codahale.metrics.MetricRegistry;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import jakarta.ws.rs.client.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.ALL_ROUTES;
import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.AVAILABLE;
import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.CONNECTIONS;
import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.INFERENCE_ROUTE;
import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.LEASED;
import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.MAX;
import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.PENDING;
import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.ROUTE;
import static com.comet.opik.infrastructure.http.HttpClientPoolMetrics.STATE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Shared HTTP client pool metrics")
class HttpClientPoolMetricsTest {

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();
    private final String path = "/" + UUID.randomUUID();
    // the builder has no Dropwizard environment, so the client's threads come from here, as in TestHttpClientUtils
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    });

    @BeforeAll
    void setUpAll() {
        wireMock.server().stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
        executor.shutdownNow();
    }

    @Test
    @DisplayName("reports the pool's totals and the route to opik-inference apart")
    void reportsTotalsAndTheInferenceRoute() {
        var reader = InMemoryMetricReader.create();
        var config = TestConfigUtils.loadConfigTest().getJerseyClient();

        try (var client = client(reader, url("localhost"))) {
            // the pool has not seen the route yet: nothing in use, and the per-route limit as its max
            assertThat(gauge(reader, MAX, INFERENCE_ROUTE, null)).isEqualTo(config.getMaxConnectionsPerRoute());
            assertThat(gauge(reader, CONNECTIONS, INFERENCE_ROUTE, AVAILABLE)).isZero();

            // one call to the inference host and one to another host: two idle connections, one of them on its route
            assertThat(client.target(url("localhost")).request().get(String.class)).isEqualTo("ok");
            assertThat(client.target(url("127.0.0.1")).request().get(String.class)).isEqualTo("ok");

            assertThat(gauge(reader, MAX, ALL_ROUTES, null)).isEqualTo(config.getMaxConnections());
            assertThat(gauge(reader, CONNECTIONS, ALL_ROUTES, AVAILABLE)).isEqualTo(2);
            assertThat(gauge(reader, CONNECTIONS, ALL_ROUTES, LEASED)).isZero();
            assertThat(gauge(reader, CONNECTIONS, INFERENCE_ROUTE, AVAILABLE)).isEqualTo(1);
            assertThat(gauge(reader, CONNECTIONS, INFERENCE_ROUTE, LEASED)).isZero();
            assertThat(gauge(reader, PENDING, INFERENCE_ROUTE, null)).isZero();
        }
    }

    @Test
    @DisplayName("leaves another port on the opik-inference host out of its route")
    void leavesAnotherPortOutOfTheInferenceRoute() {
        var reader = InMemoryMetricReader.create();

        // opik-inference on the default port, and a call to the same host on WireMock's
        try (var client = client(reader, "http://localhost")) {
            assertThat(client.target(url("localhost")).request().get(String.class)).isEqualTo("ok");

            assertThat(gauge(reader, CONNECTIONS, ALL_ROUTES, AVAILABLE)).isEqualTo(1);
            assertThat(gauge(reader, CONNECTIONS, INFERENCE_ROUTE, AVAILABLE)).isZero();
        }
    }

    @Test
    @DisplayName("names no route when no feature is set to call opik-inference")
    void namesNoRouteWithoutAnInferenceHost() {
        var reader = InMemoryMetricReader.create();

        try (var client = client(reader, null)) {
            assertThat(points(reader, CONNECTIONS).map(point -> point.getAttributes().get(ROUTE)))
                    .isNotEmpty()
                    .containsOnly(ALL_ROUTES);
        }
    }

    /** A client built as the shared one is, publishing its pool's metrics to the reader. */
    private Client client(InMemoryMetricReader reader, String inferenceUrl) {
        var builder = new DestinationGuardJerseyClientBuilder(new MetricRegistry(),
                pool -> HttpClientPoolMetrics.publish(meter(reader), pool, inferenceUrl));
        return HttpModule.buildClient(builder.using(executor), TestConfigUtils.loadConfigTest().getJerseyClient());
    }

    private String url(String host) {
        return "http://%s:%d%s".formatted(host, wireMock.server().port(), path);
    }

    private static Meter meter(InMemoryMetricReader reader) {
        return SdkMeterProvider.builder().registerMetricReader(reader).build().get("opik.http.client");
    }

    private static Stream<LongPointData> points(InMemoryMetricReader reader, String name) {
        return reader.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> metric.getLongGaugeData().getPoints().stream());
    }

    /** The one point of a gauge for a route and, when given, a state. */
    private static long gauge(InMemoryMetricReader reader, String name, String route, String state) {
        var values = points(reader, name)
                .filter(point -> route.equals(point.getAttributes().get(ROUTE))
                        && (state == null || state.equals(point.getAttributes().get(STATE))))
                .map(LongPointData::getValue)
                .toList();
        assertThat(values).as("%s for route %s, state %s", name, route, state).hasSize(1);
        return values.getFirst();
    }
}
