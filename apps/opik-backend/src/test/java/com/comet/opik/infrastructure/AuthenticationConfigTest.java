package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AuthenticationConfigTest {

    private final Validator validator = Validators.newValidator();

    private static AuthenticationConfig organizationCache(Duration fresh, Duration stale) {
        var config = new AuthenticationConfig();
        config.setOrganizationCacheTTL(fresh);
        config.setOrganizationCacheStaleTTL(stale);
        return config;
    }

    @Test
    void organizationCacheDefaultsAreValid() {
        var config = new AuthenticationConfig();

        assertThat(validator.validateProperty(config, "organizationCacheTtlOrderValid")).isEmpty();
        assertThat(validator.validateProperty(config, "organizationCacheTTL")).isEmpty();
        assertThat(validator.validateProperty(config, "organizationCacheStaleTTL")).isEmpty();
    }

    @Test
    void organizationCacheStaleTtlEqualToTheFreshOneIsValid() {
        var config = organizationCache(Duration.minutes(10), Duration.minutes(10));

        assertThat(validator.validateProperty(config, "organizationCacheTtlOrderValid")).isEmpty();
    }

    @Test
    void organizationCacheStaleTtlShorterThanTheFreshOneIsRejected() {
        var config = organizationCache(Duration.hours(24), Duration.minutes(10));

        assertThat(validator.validateProperty(config, "organizationCacheTtlOrderValid"))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(
                        "authentication.organizationCacheStaleTTL must be at least authentication.organizationCacheTTL");
    }

    private static Stream<Arguments> organizationCacheTtlsOverTheirMaximum() {
        return Stream.of(
                arguments("organizationCacheTTL", organizationCache(Duration.days(2), Duration.days(3))),
                arguments("organizationCacheStaleTTL", organizationCache(Duration.minutes(10), Duration.days(8))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("organizationCacheTtlsOverTheirMaximum")
    void organizationCacheTtlOverItsMaximumIsRejected(String property, AuthenticationConfig config) {
        assertThat(validator.validateProperty(config, property)).hasSize(1);
    }
}
