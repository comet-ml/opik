package com.comet.opik.infrastructure.llm;

import com.google.cloud.vertexai.Transport;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Vertex AI client config validation")
class VertexAIClientConfigTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        // Dropwizard's validator registers the @MinDuration constraint validator.
        validator = Validators.newValidator();
    }

    private VertexAIClientConfig.VertexAIClientConfigBuilder validConfig() {
        return VertexAIClientConfig.builder()
                .scope("https://www.googleapis.com/auth/cloud-platform")
                .multiRegionApiEndpoints(Map.of("global", "aiplatform.googleapis.com"))
                .transport(Transport.GRPC);
    }

    // null = unset, so the value comes from config.yml; 5m is the floor, 15m the shipped default.
    static Stream<Duration> acceptedTimeouts() {
        return Stream.of(null, Duration.minutes(5), Duration.minutes(15));
    }

    @ParameterizedTest
    @MethodSource("acceptedTimeouts")
    @DisplayName("a clientIdleTimeout at/above the 5m floor (or unset) is accepted")
    void acceptedIdleTimeoutsPassValidation(Duration timeout) {
        assertThat(validator.validate(validConfig().clientIdleTimeout(timeout).build())).isEmpty();
    }

    // 0 would disable the cache and reinstate the leak; the local-test 1m and 4m sit below the call-safe floor.
    static Stream<Duration> rejectedTimeouts() {
        return Stream.of(Duration.seconds(0), Duration.minutes(1), Duration.minutes(4));
    }

    @ParameterizedTest
    @MethodSource("rejectedTimeouts")
    @DisplayName("a clientIdleTimeout below the 5m floor (incl. 0) is rejected")
    void belowFloorIdleTimeoutsAreRejected(Duration timeout) {
        assertThat(validator.validate(validConfig().clientIdleTimeout(timeout).build()))
                .anyMatch(v -> v.getPropertyPath().toString().equals("clientIdleTimeout"));
    }
}
