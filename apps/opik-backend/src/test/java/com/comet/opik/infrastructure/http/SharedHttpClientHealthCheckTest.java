package com.comet.opik.infrastructure.http;

import com.comet.opik.infrastructure.SharedHttpClientHealthCheckConfig;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.apache.hc.client5.http.impl.ConnectionShutdownException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SharedHttpClientHealthCheckTest {

    private Client client;
    private Invocation.Builder requestBuilder;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        var webTarget = mock(WebTarget.class);
        requestBuilder = mock(Invocation.Builder.class);
        when(client.target(any(URI.class))).thenReturn(webTarget);
        when(webTarget.property(anyString(), any())).thenReturn(webTarget);
        when(webTarget.request()).thenReturn(requestBuilder);
    }

    private SharedHttpClientHealthCheck newHealthCheck() {
        var config = new SharedHttpClientHealthCheckConfig();
        config.setTimeout(Duration.seconds(1));
        return new SharedHttpClientHealthCheck(client, config);
    }

    static Stream<Arguments> probeFailures() {
        return Stream.of(
                arguments(new IllegalStateException("Connection pool shut down"), false),
                arguments(new ConnectionShutdownException(), false),
                arguments(new ProcessingException(new ConnectionShutdownException()), false),
                arguments(new ProcessingException(new IllegalStateException("Connection pool shut down")), false),
                arguments(new RuntimeException("wrapper",
                        new ProcessingException(new IllegalStateException("Connection pool shut down"))), false),
                arguments(new IllegalStateException("CONNECTION POOL SHUT DOWN"), false),
                arguments(new IllegalStateException("some other illegal state"), true),
                arguments(new ProcessingException(new SocketTimeoutException("Read timed out")), true),
                arguments(new ProcessingException(new IOException("Connection refused")), true),
                arguments(new IllegalStateException((String) null), true));
    }

    @ParameterizedTest
    @MethodSource("probeFailures")
    void checkReportsUnhealthyOnlyOnDeadPool(RuntimeException probeFailure, boolean expectedHealthy) {
        when(requestBuilder.head()).thenThrow(probeFailure);

        assertThat(newHealthCheck().check().isHealthy()).isEqualTo(expectedHealthy);
    }

    @Test
    void checkReportsHealthyWhenProbeReturnsResponse() {
        var response = mock(Response.class);
        when(requestBuilder.head()).thenReturn(response);

        assertThat(newHealthCheck().check().isHealthy()).isTrue();
        verify(response).close();
    }
}
