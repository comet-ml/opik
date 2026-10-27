package com.comet.opik.infrastructure.net;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class LoggableUrlTest {

    private static Stream<Arguments> keepsSchemeHostPortAndPathOnly() {
        var secret = RandomStringUtils.secure().nextAlphanumeric(16);
        var path = "/%s".formatted(RandomStringUtils.secure().nextAlphanumeric(8));
        return Stream.of(
                arguments("http://localhost:11434%s".formatted(path), "http://localhost:11434%s".formatted(path)),
                arguments("https://media.example.com%s".formatted(path), "https://media.example.com%s".formatted(path)),
                arguments("http://user:%s@localhost:11434%s".formatted(secret, path),
                        "http://localhost:11434%s".formatted(path)),
                arguments("https://media.example.com:8443%s?X-Amz-Signature=%s#%s".formatted(path, secret, secret),
                        "https://media.example.com:8443%s".formatted(path)),
                arguments("https://media.example.com/%s".formatted("a".repeat(100)),
                        "https://media.example.com/%s".formatted("a".repeat(24))),
                arguments("http://bad host/%s".formatted(secret), "<invalid URL>"),
                arguments(null, "<no URL>"));
    }

    @ParameterizedTest
    @MethodSource
    void keepsSchemeHostPortAndPathOnly(String url, String expectedLoggable) {
        var actualLoggable = LoggableUrl.of(url);

        assertThat(actualLoggable).isEqualTo(expectedLoggable);
    }
}
