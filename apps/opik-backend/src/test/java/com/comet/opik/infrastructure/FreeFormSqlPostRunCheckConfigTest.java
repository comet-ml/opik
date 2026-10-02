package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Free-form SQL post-run check config")
class FreeFormSqlPostRunCheckConfigTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("a sub-second flush interval is rejected: the cluster-wide permit counts whole seconds")
    void subSecondFlushIntervalIsRejected() {
        var config = new FreeFormSqlPostRunCheckConfig();
        assertThat(validator.validate(config)).isEmpty();

        config.setMinFlushInterval(Duration.milliseconds(500));
        assertThat(validator.validate(config)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("minFlushInterval");
    }
}
