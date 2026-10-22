package com.comet.opik.api.resources.utils.resources;

import jakarta.ws.rs.core.GenericType;
import lombok.Builder;
import org.apache.hc.core5.http.HttpStatus;
import org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class HealthCheckResourceClient {

    private static final String RESOURCE_PATH = "%s/health-check?name=%s";
    private static final GenericType<List<HealthCheckResponse>> HEALTH_CHECK_LIST = new GenericType<>() {
    };

    private final ClientSupport client;
    private final String baseURI;

    public HealthCheckResourceClient(ClientSupport client, String baseURI) {
        this.client = client;
        this.baseURI = baseURI;
    }

    @Builder(toBuilder = true)
    public record HealthCheckResponse(String name, boolean healthy, boolean critical, String type) {
    }

    /**
     * Polls {@code /health-check?name=...} until it answers OK with {@code expected}. Required because Dropwizard's
     * health endpoint returns cached state from the periodic scheduler; containers take a probe cycle or two after app
     * start to be reflected. The aggressive schedule in {@code config-test.yml} (100 ms interval, single-attempt
     * thresholds) keeps the window short, so a broken probe never satisfies the assertion before the timeout.
     */
    public void awaitHealthCheck(String name, List<HealthCheckResponse> expected) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    try (var response = client.target(RESOURCE_PATH.formatted(baseURI, name)).request().get()) {
                        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                        assertThat(response.readEntity(HEALTH_CHECK_LIST))
                                .containsExactlyInAnyOrderElementsOf(expected);
                    }
                });
    }

    /**
     * Reads the health check body without asserting the HTTP status: an unhealthy {@code critical} check makes the
     * endpoint return a non-OK status while still carrying the JSON results.
     */
    public List<HealthCheckResponse> readHealthCheck(String name) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI, name)).request().get()) {
            return response.readEntity(HEALTH_CHECK_LIST);
        }
    }
}
