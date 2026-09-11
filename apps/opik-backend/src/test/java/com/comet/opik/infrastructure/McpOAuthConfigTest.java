package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("MCP OAuth Config Validation Test")
class McpOAuthConfigTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validators.newValidator();
    }

    private static McpOAuthConfig.McpOAuthConfigBuilder validConfig() {
        return McpOAuthConfig.builder()
                .enabled(true)
                .baseUrl("https://www.comet.com/opik")
                .accessTokenTtl(Duration.ofHours(1))
                .refreshTokenTtl(Duration.ofDays(7))
                .refreshTokenAbsoluteTtl(Duration.ofDays(30))
                .codeTtl(Duration.ofSeconds(60))
                .refreshRotationGrace(Duration.ofMinutes(2))
                .refreshRotationMaxRetries(10)
                .refreshLockLease(Duration.ofSeconds(10))
                .scrubLockTimeout(Duration.ofMinutes(5))
                .scrubLockWaitTime(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("the shipped defaults pass validation")
    void validConfigHasNoViolations() {
        assertThat(validator.validate(validConfig().build())).isEmpty();
    }

    @Test
    @DisplayName("a config block that predates the newer settings still validates through the Java defaults")
    void legacyConfigBlockFallsBackToDefaults() {
        var config = McpOAuthConfig.builder()
                .enabled(true)
                .baseUrl("https://www.comet.com/opik")
                .accessTokenTtl(Duration.ofHours(1))
                .refreshTokenTtl(Duration.ofDays(7))
                .codeTtl(Duration.ofSeconds(60))
                .refreshRotationGrace(Duration.ofSeconds(30))
                .scrubLockTimeout(Duration.ofMinutes(5))
                .scrubLockWaitTime(Duration.ofMillis(100))
                .build();

        assertThat(validator.validate(config)).isEmpty();
        assertThat(config.getRefreshTokenAbsoluteTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(config.getRefreshRotationMaxRetries()).isEqualTo(10);
        assertThat(config.getRefreshLockLease()).isEqualTo(Duration.ofSeconds(10));
    }

    private static Stream<Arguments> rejectedValues() {
        return Stream.of(
                arguments("refreshTokenAbsoluteTtlPositive", "zero absolute TTL",
                        (UnaryOperator<McpOAuthConfig.McpOAuthConfigBuilder>) b -> b
                                .refreshTokenAbsoluteTtl(Duration.ZERO)),
                arguments("refreshTokenAbsoluteTtlPositive", "negative absolute TTL",
                        (UnaryOperator<McpOAuthConfig.McpOAuthConfigBuilder>) b -> b
                                .refreshTokenAbsoluteTtl(Duration.ofDays(-1))),
                arguments("refreshTokenTtlPositive", "zero idle TTL",
                        (UnaryOperator<McpOAuthConfig.McpOAuthConfigBuilder>) b -> b
                                .refreshTokenTtl(Duration.ZERO)),
                arguments("refreshLockLeasePositive", "zero lock lease",
                        (UnaryOperator<McpOAuthConfig.McpOAuthConfigBuilder>) b -> b
                                .refreshLockLease(Duration.ZERO)),
                arguments("refreshRotationMaxRetries", "zero retry cap",
                        (UnaryOperator<McpOAuthConfig.McpOAuthConfigBuilder>) b -> b
                                .refreshRotationMaxRetries(0)));
    }

    @ParameterizedTest(name = "{1} is rejected")
    @MethodSource("rejectedValues")
    void invalidValuesAreRejected(String property, String description,
            UnaryOperator<McpOAuthConfig.McpOAuthConfigBuilder> mutate) {
        Set<ConstraintViolation<McpOAuthConfig>> violations = validator.validate(mutate.apply(validConfig()).build());

        assertThat(violations).as(description)
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(property));
    }

    @Test
    @DisplayName("an absolute TTL shorter than the idle TTL is not an error: the idle TTL wins as the effective cap")
    void absoluteTtlShorterThanIdle_isRaisedToIdle() {
        var config = validConfig()
                .refreshTokenTtl(Duration.ofDays(60))
                .refreshTokenAbsoluteTtl(Duration.ofDays(30))
                .build();

        assertThat(validator.validate(config)).isEmpty();
        assertThat(config.effectiveRefreshTokenAbsoluteTtl()).isEqualTo(Duration.ofDays(60));
    }
}
