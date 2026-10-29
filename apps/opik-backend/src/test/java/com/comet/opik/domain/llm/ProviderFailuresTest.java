package com.comet.opik.domain.llm;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLHandshakeException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderFailuresTest {

    @Test
    void redactedKeepsClassesAndFramesWithoutMessages() {
        var root = new SocketTimeoutException(RandomStringUtils.secure().nextAlphanumeric(20));
        var middle = new IOException(RandomStringUtils.secure().nextAlphanumeric(20), root);
        var failure = new UncheckedIOException(RandomStringUtils.secure().nextAlphanumeric(20), middle);

        var actual = ProviderFailures.redacted(failure);

        assertRedacted(actual, failure);
        assertRedacted(actual.getCause(), middle);
        assertRedacted(actual.getCause().getCause(), root);
        assertThat(actual.getCause().getCause().getCause()).isNull();
        assertThat(ProviderFailures.rootType(failure)).isEqualTo(SocketTimeoutException.class.getSimpleName());
    }

    @Test
    void causesThatLoopKeepTheLinksBeforeTheLoop() {
        var inner = new IOException(RandomStringUtils.secure().nextAlphanumeric(20));
        var outer = new IllegalStateException(RandomStringUtils.secure().nextAlphanumeric(20), inner);
        inner.initCause(outer);

        var actual = ProviderFailures.redacted(outer);

        assertRedacted(actual, outer);
        assertRedacted(actual.getCause(), inner);
        assertThat(actual.getCause().getCause()).isNull();
        assertThat(ProviderFailures.rootType(outer)).isEqualTo(IOException.class.getSimpleName());
    }

    static Stream<Throwable> connectFailures() {
        return Stream.of(
                new ConnectException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new NoRouteToHostException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new SSLHandshakeException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new UnknownHostException(RandomStringUtils.secure().nextAlphanumeric(20)));
    }

    /** Whether, where and how a destination could be reached would tell a caller about the network it is on. */
    @ParameterizedTest
    @MethodSource("connectFailures")
    void redactedDropsTheMessageOfAFailureToConnect(Throwable root) {
        var failure = new UncheckedIOException(new IOException(root));

        var actual = ProviderFailures.redacted(failure);

        assertRedacted(actual.getCause().getCause(), root);
    }

    private static void assertRedacted(Throwable actual, Throwable expected) {
        assertThat(actual).isInstanceOf(ProviderFailures.RedactedThrowable.class);
        assertThat(actual.getMessage()).isEqualTo(expected.getClass().getName());
        assertThat(actual.getStackTrace()).isEqualTo(expected.getStackTrace());
    }
}
