package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Startup validation for the auth-call timeout and retry settings.
 * <p>
 * These constraints are the only thing standing between a typo in a deployment's configuration and
 * a silently degraded auth path — a negative retry count, a backoff of days, or a min above the max.
 * {@code config-test.yml} only exercises the valid shipped values, so without these tests a change
 * that widened or dropped a bound would not fail anything.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationConfigTest {

    private static Validator validator;

    @BeforeAll
    static void setUpAll() {
        validator = Validators.newValidator();
    }

    private static AuthenticationConfig shippedDefaults() {
        var config = new AuthenticationConfig();
        config.setRequestTimeout(Duration.seconds(3));
        config.setRequestMaxRetries(1);
        config.setRequestRetryMinBackoff(Duration.milliseconds(250));
        config.setRequestRetryMaxBackoff(Duration.seconds(1));
        return config;
    }

    @Test
    void validate__whenShippedValues__thenNoViolations() {
        assertThat(validator.validate(shippedDefaults())).isEmpty();
    }

    /**
     * The whole block being absent is the case that matters operationally: {@code OpikConfiguration}
     * cascades {@code @Valid} into a default instance, so a config file omitting {@code
     * authentication:} reaches validation with every setting null. That must fail at startup naming
     * the properties, not bind silently.
     */
    @Test
    void validate__whenSettingsOmitted__thenFailsNamingEachProperty() {
        var violations = validator.validate(new AuthenticationConfig());

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("requestTimeout", "requestMaxRetries", "requestRetryMinBackoff",
                        "requestRetryMaxBackoff");
    }

    static Stream<Arguments> boundaryArgs() {
        return Stream.of(
                // requestTimeout: 0 means "inherit the shared client timeout"; 30s is the shared
                // jerseyClient timeout, above which the override would be inert.
                arguments("requestTimeout at lower bound", mutate(c -> c.setRequestTimeout(Duration.seconds(0))), true),
                arguments("requestTimeout at upper bound", mutate(c -> c.setRequestTimeout(Duration.seconds(30))),
                        true),
                arguments("requestTimeout above upper bound",
                        mutate(c -> c.setRequestTimeout(Duration.seconds(31))), false),
                arguments("requestTimeout negative", mutate(c -> c.setRequestTimeout(Duration.milliseconds(-1))),
                        false),
                // requestMaxRetries: 0 disables retries, 5 is the deliberate cap.
                arguments("requestMaxRetries at lower bound", mutate(c -> c.setRequestMaxRetries(0)), true),
                arguments("requestMaxRetries at upper bound", mutate(c -> c.setRequestMaxRetries(5)), true),
                arguments("requestMaxRetries above cap", mutate(c -> c.setRequestMaxRetries(6)), false),
                arguments("requestMaxRetries negative", mutate(c -> c.setRequestMaxRetries(-1)), false),
                // backoffs must be positive and bounded.
                arguments("minBackoff zero", mutate(c -> c.setRequestRetryMinBackoff(Duration.milliseconds(0))),
                        false),
                arguments("minBackoff above upper bound",
                        mutate(c -> c.setRequestRetryMinBackoff(Duration.seconds(11))), false),
                arguments("maxBackoff above upper bound",
                        mutate(c -> c.setRequestRetryMaxBackoff(Duration.seconds(31))), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaryArgs")
    void validate__boundaries(String name, AuthenticationConfig config, boolean expectedValid) {
        assertThat(validator.validate(config).isEmpty()).isEqualTo(expectedValid);
    }

    /**
     * The cross-field check. Reactor's {@code Retry.backoff} would clamp a contradictory range
     * silently rather than fail, so this has to be caught at startup.
     */
    @Test
    void validate__whenMinBackoffExceedsMaxBackoff__thenFails() {
        var config = shippedDefaults();
        config.setRequestRetryMinBackoff(Duration.seconds(2));
        config.setRequestRetryMaxBackoff(Duration.seconds(1));

        assertThat(validator.validate(config))
                .extracting(v -> v.getMessage())
                .anyMatch(message -> message.contains("requestRetryMinBackoff")
                        && message.contains("requestRetryMaxBackoff"));
    }

    @Test
    void validate__whenMinBackoffEqualsMaxBackoff__thenAllowed() {
        var config = shippedDefaults();
        config.setRequestRetryMinBackoff(Duration.seconds(1));
        config.setRequestRetryMaxBackoff(Duration.seconds(1));

        assertThat(validator.validate(config)).isEmpty();
    }

    private static AuthenticationConfig mutate(java.util.function.Consumer<AuthenticationConfig> change) {
        var config = shippedDefaults();
        change.accept(config);
        return config;
    }
}
