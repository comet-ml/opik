package com.comet.opik.infrastructure.http;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared client applies a request's destination guard at connect time and leaves other requests alone. The mock
 * server is on loopback, which the strict guard refuses, so no pre-flight check is involved here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Destination guard on the shared Jersey client")
class DestinationGuardJerseyClientBuilderTest {

    private static final DestinationGuard STRICT = DestinationGuard.builder()
            .mode(DestinationGuard.Mode.STRICT)
            .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
            .build();

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();
    private final Client client = TestHttpClientUtils.client();

    private String path;
    private String body;

    @BeforeEach
    void setUp() {
        path = "/" + UUID.randomUUID();
        body = UUID.randomUUID().toString();
        wireMock.server().stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(200).withBody(body)));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1"})
    @DisplayName("a guarded request isn't connected to a refused address, and the next unguarded one is")
    void refusesGuardedRequestOnly(String host) {
        var url = url(host);

        assertThatThrownBy(() -> client.target(url)
                .request()
                .property(DestinationGuardDnsResolver.PROPERTY, STRICT)
                .get()
                .close())
                .isInstanceOf(ProcessingException.class)
                .matches(DestinationGuardDns::isRefused);
        assertThat(wireMock.server().findAll(anyRequestedFor(urlPathEqualTo(path)))).isEmpty();

        try (var response = client.target(url).request().get()) {
            assertOk(response);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1"})
    @DisplayName("an async guarded request isn't connected to a refused address")
    void refusesAsyncGuardedRequest(String host) {
        var future = client.target(url(host))
                .request()
                .property(DestinationGuardDnsResolver.PROPERTY, STRICT)
                .async()
                .get();

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .matches(DestinationGuardDns::isRefused);
        assertThat(wireMock.server().findAll(anyRequestedFor(urlPathEqualTo(path)))).isEmpty();
    }

    @Test
    @DisplayName("an unguarded request and one whose guard allows the address connect as usual")
    void connectsAllowedRequests() {
        var relaxed = STRICT.toBuilder().mode(DestinationGuard.Mode.RELAXED).build();

        try (var response = client.target(url("localhost")).request().get()) {
            assertOk(response);
        }
        try (var response = client.target(url("localhost"))
                .request()
                .property(DestinationGuardDnsResolver.PROPERTY, relaxed)
                .get()) {
            assertOk(response);
        }
    }

    private String url(String host) {
        return "http://%s:%d%s".formatted(host, wireMock.server().port(), path);
    }

    private void assertOk(Response response) {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.readEntity(String.class)).isEqualTo(body);
    }
}
