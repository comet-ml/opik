package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Optimization Stalled Reaper Config Validation Test")
class OptimizationStalledReaperConfigTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        // Dropwizard's validator registers @MinDuration/@MaxDuration constraint validators.
        validator = Validators.newValidator();
    }

    private OptimizationStalledReaperConfig.OptimizationStalledReaperConfigBuilder validConfig() {
        return OptimizationStalledReaperConfig.builder()
                .enabled(true)
                .startupDelay(Duration.minutes(5))
                .jobInterval(Duration.minutes(5))
                .initializedTimeout(Duration.minutes(5))
                .runningTimeout(Duration.hours(8))
                .lookbackMargin(Duration.days(7))
                .lockDuration(Duration.minutes(4))
                .batchSize(100);
    }

    @Test
    @DisplayName("a config with sane defaults passes validation")
    void validConfigHasNoViolations() {
        assertThat(validator.validate(validConfig().build())).isEmpty();
    }

    @Test
    @DisplayName("runningTimeout below the 6h worker-timeout floor fails validation")
    void runningTimeoutBelowFloorIsRejected() {
        var config = validConfig().runningTimeout(Duration.hours(1)).build();

        Set<ConstraintViolation<OptimizationStalledReaperConfig>> violations = validator.validate(config);

        assertThat(violations)
                .as("a below-worker-timeout runningTimeout must fail fast at boot, not silently reap live runs")
                .anyMatch(v -> v.getPropertyPath().toString().equals("runningTimeout"));
    }

    @Test
    @DisplayName("lockDuration >= jobInterval fails the @AssertTrue invariant")
    void lockDurationNotBelowJobIntervalIsRejected() {
        var config = validConfig()
                .lockDuration(Duration.minutes(5))
                .jobInterval(Duration.minutes(5))
                .build();

        assertThat(validator.validate(config))
                .anyMatch(v -> v.getMessage().contains("lockDuration must be less than jobInterval"));
    }
}
