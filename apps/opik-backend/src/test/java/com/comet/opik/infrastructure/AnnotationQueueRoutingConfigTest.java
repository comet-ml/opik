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

@DisplayName("Annotation Queue Routing Config Validation Test")
class AnnotationQueueRoutingConfigTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validators.newValidator();
    }

    /** The shipped values from config.yml. */
    private AnnotationQueueRoutingConfig.AnnotationQueueRoutingConfigBuilder validConfig() {
        return AnnotationQueueRoutingConfig.builder()
                .enabled(false)
                .jobEnabled(true)
                .streamName("annotation-queue-routing")
                .consumerGroupName("annotation-queue-routing-consumers")
                .consumerBatchSize(100)
                .debounceDelay(Duration.seconds(15))
                .bufferTtl(Duration.minutes(2))
                .jobInterval(Duration.seconds(5))
                .jobLockTime(Duration.seconds(4))
                .jobLockWaitTime(Duration.milliseconds(300))
                .jobBatchSize(1000)
                .poolingInterval(Duration.seconds(1))
                .longPollingDuration(Duration.seconds(5))
                .maxRetries(3)
                .claimIntervalRatio(10)
                .pendingMessageDuration(Duration.minutes(5))
                .streamMaxLen(100_000)
                .streamTrimLimit(1000);
    }

    @Test
    @DisplayName("the shipped values pass validation")
    void shippedValuesHaveNoViolations() {
        assertThat(validator.validate(validConfig().build())).isEmpty();
    }

    @Test
    @DisplayName("a lock held as long as the interval fails validation")
    void lockTimeAtOrAboveIntervalIsRejected() {
        var config = validConfig().jobLockTime(Duration.seconds(5)).build();

        Set<ConstraintViolation<AnnotationQueueRoutingConfig>> violations = validator.validate(config);

        assertThat(violations)
                .as("a hold-until-expiry lock at or above the interval would make every other tick a no-op")
                .anyMatch(v -> v.getPropertyPath().toString().equals("jobLockTimeBelowJobInterval"));
    }

    @Test
    @DisplayName("a buffer TTL shorter than the flush interval fails validation")
    void bufferTtlBelowIntervalIsRejected() {
        var config = validConfig().jobInterval(Duration.seconds(30)).bufferTtl(Duration.seconds(10))
                .jobLockTime(Duration.seconds(4)).build();

        Set<ConstraintViolation<AnnotationQueueRoutingConfig>> violations = validator.validate(config);

        assertThat(violations)
                .as("the TTL is renewed only by flush runs, so it must outlive the gap between two of them")
                .anyMatch(v -> v.getPropertyPath().toString().equals("bufferTtlAboveJobInterval"));
    }
}
