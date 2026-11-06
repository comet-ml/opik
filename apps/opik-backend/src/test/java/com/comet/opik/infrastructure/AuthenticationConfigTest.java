package com.comet.opik.infrastructure;

import com.comet.opik.TestConfigUtils;
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

    private static final String FRESH = "organizationCacheTTL";
    private static final String STALE = "organizationCacheStaleTTL";
    private static final String RETRY = "organizationCacheRetryAfterFailure";

    private final Validator validator = Validators.newValidator();

    private static AuthenticationConfig organizationCache(Duration fresh, Duration stale, Duration retry) {
        return AuthenticationConfig.builder()
                .organizationCacheTTL(fresh)
                .organizationCacheStaleTTL(stale)
                .organizationCacheRetryAfterFailure(retry)
                .build();
    }

    private static AuthenticationConfig organizationCache(Duration fresh, Duration stale) {
        return organizationCache(fresh, stale, Duration.seconds(30));
    }

    @Test
    void organizationCacheSettingsHaveNoDefaultsInCode() {
        var config = new AuthenticationConfig();

        for (var property : new String[]{FRESH, STALE, RETRY}) {
            assertThat(validator.validateProperty(config, property))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactly("must not be null");
        }
    }

    @Test
    void organizationCacheSettingsComeFromTheConfigFile() {
        var config = TestConfigUtils.loadConfigTest().getAuthentication();

        assertThat(config.getOrganizationCacheTTL()).isNotNull();
        assertThat(config.getOrganizationCacheStaleTTL()).isNotNull();
        assertThat(config.getOrganizationCacheRetryAfterFailure()).isNotNull();
        assertThat(validator.validate(config)).isEmpty();
    }

    @Test
    void organizationCacheStaleTtlEqualToTheFreshOneIsValid() {
        var config = organizationCache(Duration.minutes(10), Duration.minutes(10));

        assertThat(validator.validateProperty(config, "organizationCacheTtlOrderValid")).isEmpty();
    }

    @Test
    void organizationCacheStaleTtlShorterThanTheFreshOneIsRejected() {
        var config = organizationCache(Duration.minutes(30), Duration.minutes(10));

        assertThat(validator.validateProperty(config, "organizationCacheTtlOrderValid"))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(
                        "authentication.organizationCacheStaleTTL must be at least authentication.organizationCacheTTL");
    }

    private static Stream<Arguments> organizationCacheLimits() {
        return Stream.of(
                arguments(FRESH, organizationCache(Duration.minutes(60), Duration.minutes(60)),
                        organizationCache(Duration.minutes(61), Duration.minutes(60)),
                        "must be less than or equal to 60 MINUTES"),
                arguments(FRESH, organizationCache(Duration.minutes(1), Duration.minutes(60)),
                        organizationCache(Duration.seconds(59), Duration.minutes(60)),
                        "must be greater than or equal to 1 MINUTES"),
                arguments(STALE, organizationCache(Duration.minutes(10), Duration.minutes(60)),
                        organizationCache(Duration.minutes(10), Duration.minutes(61)),
                        "must be less than or equal to 60 MINUTES"),
                arguments(STALE, organizationCache(Duration.minutes(1), Duration.minutes(1)),
                        organizationCache(Duration.minutes(1), Duration.seconds(59)),
                        "must be greater than or equal to 1 MINUTES"),
                arguments(RETRY, organizationCache(Duration.minutes(10), Duration.minutes(60), Duration.minutes(5)),
                        organizationCache(Duration.minutes(10), Duration.minutes(60), Duration.seconds(301)),
                        "must be less than or equal to 5 MINUTES"),
                arguments(RETRY, organizationCache(Duration.minutes(10), Duration.minutes(60), Duration.seconds(1)),
                        organizationCache(Duration.minutes(10), Duration.minutes(60), Duration.milliseconds(999)),
                        "must be greater than or equal to 1 SECONDS"));
    }

    @ParameterizedTest(name = "{0}: {3}")
    @MethodSource("organizationCacheLimits")
    void organizationCacheSettingAcceptsItsLimitAndRejectsAnythingPastIt(String property,
            AuthenticationConfig atLimit, AuthenticationConfig pastLimit, String message) {
        assertThat(validator.validateProperty(atLimit, property)).isEmpty();
        assertThat(validator.validateProperty(pastLimit, property))
                .extracting(violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple(property, message));
    }
}
