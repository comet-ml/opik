package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Media URL Check Config Validation Test")
class MediaUrlCheckConfigTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validators.newValidator();
    }

    @Test
    @DisplayName("the defaults pass validation")
    void defaultConfigHasNoViolations() {
        assertThat(validator.validate(new MediaUrlCheckConfig())).isEmpty();
    }

    @Test
    @DisplayName("a per-URL timeout of 6s is accepted")
    void perUrlTimeoutAtCeilingIsAccepted() {
        var config = new MediaUrlCheckConfig();
        config.setPerUrlTimeout(Duration.seconds(6));

        assertThat(validator.validate(config)).isEmpty();
    }

    @Test
    @DisplayName("a per-URL timeout the frontend would not wait for is rejected")
    void perUrlTimeoutAboveCeilingIsRejected() {
        var config = new MediaUrlCheckConfig();
        config.setPerUrlTimeout(Duration.milliseconds(6001));

        assertThat(validator.validate(config))
                .as("the frontend gives up after 8s and runs without the check, so a slower verdict would be lost")
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("perUrlTimeout"));
    }
}
