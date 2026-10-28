package com.comet.opik.domain.llm;

import com.comet.opik.infrastructure.net.RefusedHostException;
import dev.langchain4j.exception.HttpException;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLHandshakeException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
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

    static Stream<Throwable> aFailureToReachTheProviderKeepsItsMessage() {
        return Stream.of(
                new SocketException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new ConnectException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new NoRouteToHostException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new UnknownHostException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new SSLHandshakeException(RandomStringUtils.secure().nextAlphanumeric(20)));
    }

    /** What the JDK reports for a socket that can't connect is no text of the provider's, and names the cause. */
    @ParameterizedTest
    @MethodSource
    void aFailureToReachTheProviderKeepsItsMessage(Throwable root) {
        var failure = new UncheckedIOException(RandomStringUtils.secure().nextAlphanumeric(20), new IOException(root));
        var expectedMessage = "%s: %s".formatted(root.getClass().getName(), root.getMessage());

        var actual = ProviderFailures.redacted(failure);

        assertRedacted(actual, failure);
        assertThat(actual.getCause().getCause().getMessage()).isEqualTo(expectedMessage);
        assertThat(ProviderFailures.redacted(actual).getCause().getCause().getMessage()).isEqualTo(expectedMessage);
    }

    static Stream<Throwable> anyOtherFailureDropsItsMessage() {
        return Stream.of(
                new HttpException(502, RandomStringUtils.secure().nextAlphanumeric(20)),
                new RefusedHostException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new SocketTimeoutException(RandomStringUtils.secure().nextAlphanumeric(20)),
                new IOException(RandomStringUtils.secure().nextAlphanumeric(20)));
    }

    /** A provider's reply and the destination guard's description of a host are never kept. */
    @ParameterizedTest
    @MethodSource
    void anyOtherFailureDropsItsMessage(Throwable root) {
        var failure = new IllegalStateException(RandomStringUtils.secure().nextAlphanumeric(20), root);

        var actual = ProviderFailures.redacted(failure);

        assertRedacted(actual, failure);
        assertRedacted(actual.getCause(), root);
    }

    private static void assertRedacted(Throwable actual, Throwable expected) {
        assertThat(actual).isInstanceOf(ProviderFailures.RedactedThrowable.class);
        assertThat(actual.getMessage()).isEqualTo(expected.getClass().getName());
        assertThat(actual.getStackTrace()).isEqualTo(expected.getStackTrace());
    }
}
