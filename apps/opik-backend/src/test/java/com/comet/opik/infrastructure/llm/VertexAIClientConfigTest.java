package com.comet.opik.infrastructure.llm;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Vertex AI Client Config Validation Test")
class VertexAIClientConfigTest {

    private final Validator validator = Validators.newValidator();

    private static VertexAIClientConfig config(Duration expireAfterAccess) {
        return VertexAIClientConfig.builder()
                .scope("https://www.googleapis.com/auth/cloud-platform")
                .multiRegionApiEndpoints(Map.of())
                .tokenUris(Set.of(URI.create("https://oauth2.googleapis.com/token")))
                .credentialsCacheExpireAfterAccess(expireAfterAccess)
                .tokenCacheRefreshFraction(0.25)
                .tokenCacheMinRefreshAhead(Duration.minutes(5))
                .build();
    }

    @ParameterizedTest(name = "expireAfterAccess={0}")
    @MethodSource
    @DisplayName("the credentials cache expiry is accepted from 1 second up to 1 day")
    void expireAfterAccessWithinItsBoundsPasses(Duration expireAfterAccess) {
        assertThat(validator.validate(config(expireAfterAccess))).isEmpty();
    }

    static Stream<Duration> expireAfterAccessWithinItsBoundsPasses() {
        return Stream.of(Duration.seconds(1), Duration.minutes(15), Duration.days(1));
    }

    @ParameterizedTest(name = "expireAfterAccess={0}")
    @MethodSource
    @DisplayName("a credentials cache expiry outside its bounds fails startup")
    void expireAfterAccessPastItsBoundsFails(Duration expireAfterAccess) {
        assertThat(validator.validate(config(expireAfterAccess)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("credentialsCacheExpireAfterAccess");
    }

    static Stream<Duration> expireAfterAccessPastItsBoundsFails() {
        return Stream.of(Duration.milliseconds(999), Duration.hours(25));
    }

    @ParameterizedTest(name = "minRefreshAhead={0}")
    @MethodSource
    @DisplayName("the token's minimum refresh-ahead is accepted from the auth library's margin up to 30 minutes")
    void minRefreshAheadWithinItsBoundsPasses(Duration minRefreshAhead) {
        var config = config(Duration.minutes(15)).toBuilder()
                .tokenCacheMinRefreshAhead(minRefreshAhead)
                .build();

        assertThat(validator.validate(config)).isEmpty();
    }

    static Stream<Duration> minRefreshAheadWithinItsBoundsPasses() {
        return Stream.of(Duration.seconds(225), Duration.minutes(5), Duration.minutes(30));
    }

    /** Below the auth library's 3m45s refresh margin, a refreshing pod would be handed back the expiring token. */
    @ParameterizedTest(name = "minRefreshAhead={0}")
    @MethodSource
    @DisplayName("a minimum refresh-ahead outside its bounds fails startup")
    void minRefreshAheadPastItsBoundsFails(Duration minRefreshAhead) {
        var config = config(Duration.minutes(15)).toBuilder()
                .tokenCacheMinRefreshAhead(minRefreshAhead)
                .build();

        assertThat(validator.validate(config))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("tokenCacheMinRefreshAhead");
    }

    static Stream<Duration> minRefreshAheadPastItsBoundsFails() {
        return Stream.of(Duration.seconds(224), Duration.seconds(1801));
    }
}
