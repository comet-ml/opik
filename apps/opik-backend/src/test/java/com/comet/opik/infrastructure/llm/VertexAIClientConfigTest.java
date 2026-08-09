package com.comet.opik.infrastructure.llm;

import com.google.cloud.vertexai.Transport;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    @Test
    @DisplayName("no clientIdleTimeout is valid — the generator falls back to its default")
    void nullIdleTimeoutIsValid() {
        assertThat(validator.validate(validConfig().build())).isEmpty();
    }

    @Test
    @DisplayName("a 15m clientIdleTimeout is valid")
    void defaultScaleIdleTimeoutIsValid() {
        assertThat(validator.validate(validConfig().clientIdleTimeout(Duration.minutes(15)).build())).isEmpty();
    }

    @Test
    @DisplayName("the 5m floor is valid (boundary)")
    void atFloorIsValid() {
        assertThat(validator.validate(validConfig().clientIdleTimeout(Duration.minutes(5)).build())).isEmpty();
    }

    @Test
    @DisplayName("below the 5m floor is rejected — e.g. the local-test 1m is not a valid prod value")
    void belowFloorIsRejected() {
        var config = validConfig().clientIdleTimeout(Duration.minutes(1)).build();

        assertThat(validator.validate(config))
                .as("near a call's duration a long in-flight call can outlive the idle window and get its client closed")
                .anyMatch(v -> v.getPropertyPath().toString().equals("clientIdleTimeout"));
    }

    @Test
    @DisplayName("a zero clientIdleTimeout is rejected — it would disable the cache and reinstate the per-call leak")
    void zeroIdleTimeoutIsRejected() {
        var config = validConfig().clientIdleTimeout(Duration.seconds(0)).build();

        assertThat(validator.validate(config))
                .anyMatch(v -> v.getPropertyPath().toString().equals("clientIdleTimeout"));
    }
}
