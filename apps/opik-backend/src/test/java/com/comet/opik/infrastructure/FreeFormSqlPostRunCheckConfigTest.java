package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Free-form SQL post-run check config")
public class FreeFormSqlPostRunCheckConfigTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** The values config-test.yml sets, for tests that build the reader without the app. */
    public static FreeFormSqlPostRunCheckConfig config() {
        var config = new FreeFormSqlPostRunCheckConfig();
        config.setMinFlushInterval(Duration.seconds(1));
        config.setLogRetryDelay(Duration.milliseconds(500));
        config.setMaxFlushAttempts(3);
        return config;
    }

    @Test
    @DisplayName("an absent setting is rejected: config.yml holds the only defaults")
    void absentSettingsAreRejected() {
        assertThat(validator.validate(new FreeFormSqlPostRunCheckConfig()))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("minFlushInterval", "logRetryDelay", "maxFlushAttempts");
    }

    @Test
    @DisplayName("a sub-second flush interval is rejected: the cluster-wide permit counts whole seconds")
    void subSecondFlushIntervalIsRejected() {
        var config = config();
        assertThat(validator.validate(config)).isEmpty();

        config.setMinFlushInterval(Duration.milliseconds(500));
        assertThat(validator.validate(config)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("minFlushInterval");
    }
}
