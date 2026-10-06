package com.comet.opik.infrastructure;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AlertsEventBridgeConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "opik-alerts",
            "team/opik.alerts_1",
            "arn:aws:events:us-east-1:123456789012:event-bus/opik-alerts",
            "arn:aws-us-gov:events:us-gov-west-1:123456789012:event-bus/opik-alerts",
            "arn:aws-cn:events:cn-north-1:123456789012:event-bus/default"})
    void eventBusWhenValidHasNoViolations(String eventBus) {
        var config = new AlertsEventBridgeConfig();
        config.setEventBus(eventBus);

        assertThat(VALIDATOR.validate(config)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "opik alerts",
            "opik:alerts",
            "arn:aws:events",
            "arn:aws:events:us-east-1:123456789012:rule/opik-alerts",
            "arn:aws:events:us-east-1:1234:event-bus/opik-alerts",
            "arn:aws:events:not-a-region:123456789012:event-bus/opik-alerts",
            "arn:aws:sqs:us-east-1:123456789012:event-bus/opik-alerts"})
    void eventBusWhenInvalidReportsViolation(String eventBus) {
        var config = new AlertsEventBridgeConfig();
        config.setEventBus(eventBus);

        assertThat(VALIDATOR.validate(config))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("eventBusValid");
    }

    @Test
    void eventBusWhenNameLongerThan256ReportsViolation() {
        var config = new AlertsEventBridgeConfig();
        config.setEventBus("a".repeat(257));

        assertThat(VALIDATOR.validate(config))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("eventBusValid");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"us-east-1", "eu-central-2", "ap-southeast-4", "us-gov-west-1"})
    void regionWhenValidHasNoViolations(String region) {
        var config = new AlertsEventBridgeConfig();
        config.setRegion(region);

        assertThat(VALIDATOR.validate(config)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east", "US-EAST-1", "useast1", "us-east-1a", "us_east_1", "us-east-12"})
    void regionWhenInvalidReportsViolation(String region) {
        var config = new AlertsEventBridgeConfig();
        config.setRegion(region);

        assertThat(VALIDATOR.validate(config))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("regionValid");
    }
}
