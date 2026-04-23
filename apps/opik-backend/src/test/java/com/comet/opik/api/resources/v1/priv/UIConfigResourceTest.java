package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UIConfig;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UIConfigResource")
class UIConfigResourceTest {

    @Test
    @DisplayName("returns the configured defaultPageSize value")
    void returnsConfiguredDefaultPageSize() {
        UIConfig uiConfig = new UIConfig();
        uiConfig.setDefaultPageSize(25);

        OpikConfiguration config = new OpikConfiguration();
        config.setUiConfig(uiConfig);

        UIConfigResource resource = new UIConfigResource(config);

        Response response = resource.getUIConfig();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((UIConfig) response.getEntity()).getDefaultPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("defaults to 100 when no override is provided")
    void returnsDefaultWhenNotOverridden() {
        OpikConfiguration config = new OpikConfiguration();
        UIConfigResource resource = new UIConfigResource(config);

        Response response = resource.getUIConfig();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((UIConfig) response.getEntity()).getDefaultPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("rejects defaultPageSize below 1 or above 1000")
    void rejectsOutOfBoundsDefaultPageSize() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            UIConfig tooLow = new UIConfig();
            tooLow.setDefaultPageSize(0);
            Set<ConstraintViolation<UIConfig>> lowViolations = validator.validate(tooLow);
            assertThat(lowViolations).isNotEmpty();

            UIConfig tooHigh = new UIConfig();
            tooHigh.setDefaultPageSize(1001);
            Set<ConstraintViolation<UIConfig>> highViolations = validator.validate(tooHigh);
            assertThat(highViolations).isNotEmpty();

            UIConfig inRange = new UIConfig();
            inRange.setDefaultPageSize(500);
            Set<ConstraintViolation<UIConfig>> okViolations = validator.validate(inRange);
            assertThat(okViolations).isEmpty();
        }
    }
}
