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
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AuthenticationConfigTest {

    private final Validator validator = Validators.newValidator();

    private static AuthenticationConfig organizationCache(Duration fresh, Duration stale) {
        return AuthenticationConfig.builder()
                .organizationCacheTTL(fresh)
                .organizationCacheStaleTTL(stale)
                .build();
    }

    private static Stream<Arguments> configsWithDefaults() {
        return Stream.of(
                arguments("no-args constructor, as config.yml binds it", new AuthenticationConfig()),
                arguments("builder", AuthenticationConfig.builder().build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configsWithDefaults")
    void organizationCacheDefaultsAreSetAndValid(String name, AuthenticationConfig config) {
        assertThat(config.getOrganizationCacheTTL()).isEqualTo(Duration.minutes(10));
        assertThat(config.getOrganizationCacheStaleTTL()).isEqualTo(Duration.hours(24));
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

    private static Stream<Arguments> organizationCacheTtlLimits() {
        return Stream.of(
                arguments("organizationCacheTTL",
                        organizationCache(Duration.days(1), Duration.days(1)),
                        organizationCache(Duration.hours(25), Duration.days(2)),
                        "must be less than or equal to 1 DAYS"),
                arguments("organizationCacheStaleTTL",
                        organizationCache(Duration.minutes(10), Duration.days(7)),
                        organizationCache(Duration.minutes(10), Duration.hours(169)),
                        "must be less than or equal to 7 DAYS"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("organizationCacheTtlLimits")
    void organizationCacheTtlAcceptsItsMaximumAndRejectsAnHourMore(String property, AuthenticationConfig atMaximum,
            AuthenticationConfig overMaximum, String message) {
        assertThat(validator.validateProperty(atMaximum, property)).isEmpty();
        assertThat(validator.validateProperty(overMaximum, property))
                .extracting(violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple(property, message));
    }
}
