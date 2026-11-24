package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationConfigTest {

    private static final String RETRY_WINDOW = "organizationCacheRetryWindowValid";

    private final Validator validator = Validators.newValidator();

    private Set<String> violatedProperties(AuthenticationConfig config) {
        return validator.validate(config).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private AuthenticationConfig organizationCache(Duration staleTtl, Duration retryAfterFailure) {
        return AuthenticationConfig.builder()
                .organizationCacheTTL(Duration.minutes(1))
                .organizationCacheStaleTTL(staleTtl)
                .organizationCacheRetryAfterFailure(retryAfterFailure)
                .build();
    }

    @Test
    void retryWindowUpToTheStaleTtlIsValid() {
        assertThat(violatedProperties(organizationCache(Duration.minutes(60), Duration.seconds(30))))
                .doesNotContain(RETRY_WINDOW);
        assertThat(violatedProperties(organizationCache(Duration.minutes(2), Duration.minutes(2))))
                .doesNotContain(RETRY_WINDOW);
    }

    @Test
    void retryWindowLongerThanTheStaleTtlIsRejected() {
        assertThat(violatedProperties(organizationCache(Duration.minutes(1), Duration.minutes(5))))
                .contains(RETRY_WINDOW);
    }
}
