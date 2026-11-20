package com.comet.opik.api.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SecureHttpUrlValidatorTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private record Target(@SecureHttpUrl String url) {
    }

    static Stream<Arguments> urls() {
        return Stream.of(
                arguments("unset", null, true),
                arguments("blank", " ", true),
                arguments("https", "https://opik-inference.example.com", true),
                arguments("https, upper case scheme, with a port and a path", "HTTPS://opik-inference:8443/base", true),
                arguments("http to localhost", "http://localhost:8080", true),
                arguments("http to 127.0.0.1", "http://127.0.0.1:8080", true),
                arguments("http to [::1]", "http://[::1]:8080", true),
                arguments("http to a cluster service", "http://opik-inference.inference.svc:8000", false),
                arguments("http to a private address", "http://10.0.0.12:8000", false),
                arguments("http to a name that starts with localhost", "http://localhost.example.com", false),
                arguments("another scheme", "ftp://opik-inference.example.com", false),
                arguments("no host", "https:///v1", false),
                arguments("not a URL", "https://opik inference", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("urls")
    void httpsOrHttpToThisHost(String description, String url, boolean valid) {
        assertThat(VALIDATOR.validate(new Target(url)).isEmpty()).isEqualTo(valid);
    }
}
