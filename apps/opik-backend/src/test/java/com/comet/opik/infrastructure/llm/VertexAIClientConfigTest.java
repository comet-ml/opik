package com.comet.opik.infrastructure.llm;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Vertex AI Client Config Validation Test")
class VertexAIClientConfigTest {

    private final Validator validator = Validators.newValidator();

    private static VertexAIClientConfig config(Duration expireAfterAccess, Duration lockTimeout) {
        return VertexAIClientConfig.builder()
                .scope("https://www.googleapis.com/auth/cloud-platform")
                .multiRegionApiEndpoints(Map.of())
                .tokenUris(Set.of(URI.create("https://oauth2.googleapis.com/token")))
                .credentialsCacheExpireAfterAccess(expireAfterAccess)
                .tokenCacheRefreshFraction(0.25)
                .tokenCacheLockTimeout(lockTimeout)
                .build();
    }

    @ParameterizedTest(name = "expireAfterAccess={0}, lockTimeout={1}")
    @MethodSource
    @DisplayName("the cache durations are accepted up to and including their bounds")
    void durationsWithinTheirBoundsPass(Duration expireAfterAccess, Duration lockTimeout) {
        assertThat(validator.validate(config(expireAfterAccess, lockTimeout))).isEmpty();
    }

    static Stream<Arguments> durationsWithinTheirBoundsPass() {
        return Stream.of(
                Arguments.of(Duration.seconds(1), Duration.seconds(1)),
                Arguments.of(Duration.minutes(15), Duration.seconds(15)),
                Arguments.of(Duration.days(1), Duration.minutes(1)));
    }

    @ParameterizedTest(name = "expireAfterAccess={0}, lockTimeout={1}")
    @MethodSource
    @DisplayName("a cache duration past its bound fails startup")
    void durationsPastTheirBoundsFail(Duration expireAfterAccess, Duration lockTimeout, String expectedProperty) {
        assertThat(validator.validate(config(expireAfterAccess, lockTimeout)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly(expectedProperty);
    }

    static Stream<Arguments> durationsPastTheirBoundsFail() {
        return Stream.of(
                Arguments.of(Duration.milliseconds(999), Duration.seconds(15), "credentialsCacheExpireAfterAccess"),
                Arguments.of(Duration.hours(25), Duration.seconds(15), "credentialsCacheExpireAfterAccess"),
                Arguments.of(Duration.minutes(15), Duration.milliseconds(999), "tokenCacheLockTimeout"),
                Arguments.of(Duration.minutes(15), Duration.seconds(61), "tokenCacheLockTimeout"));
    }
}
