package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.ProcessingException;
import org.apache.hc.client5.http.impl.ConnectionShutdownException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuthHttpClientHealthCheckTest {

    static Stream<Arguments> exceptions() {
        return Stream.of(
                Arguments.of(new IllegalStateException("Connection pool shut down"), true),
                Arguments.of(new ConnectionShutdownException(), true),
                Arguments.of(new ProcessingException(new ConnectionShutdownException()), true),
                Arguments.of(new ProcessingException(new IllegalStateException("Connection pool shut down")), true),
                Arguments.of(new RuntimeException("wrapper",
                        new ProcessingException(new IllegalStateException("Connection pool shut down"))), true),
                Arguments.of(new IllegalStateException("CONNECTION POOL SHUT DOWN"), true),
                Arguments.of(new IllegalStateException("some other illegal state"), false),
                Arguments.of(new ProcessingException(new SocketTimeoutException("Read timed out")), false),
                Arguments.of(new ProcessingException(new IOException("Connection refused")), false),
                Arguments.of(new IllegalStateException((String) null), false),
                Arguments.of(null, false));
    }

    @ParameterizedTest
    @MethodSource("exceptions")
    void isConnectionPoolShutDown__detectsOnlyDeadPool(Throwable throwable, boolean expected) {
        assertThat(AuthHttpClientHealthCheck.isConnectionPoolShutDown(throwable)).isEqualTo(expected);
    }
}
