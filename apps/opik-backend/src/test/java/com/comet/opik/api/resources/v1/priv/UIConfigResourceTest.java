package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UIConfigResource")
@ExtendWith(DropwizardExtensionsSupport.class)
class UIConfigResourceTest {

    private static final int OVERRIDE_PAGE_SIZE = 25;

    private static final ObjectMapper MAPPER = Jackson.newObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE);

    private static final OpikConfiguration CONFIG = buildConfig(OVERRIDE_PAGE_SIZE);

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(MAPPER)
            .addResource(new UIConfigResource(CONFIG))
            .build();

    private static OpikConfiguration buildConfig(int pageSize) {
        UIConfig uiConfig = new UIConfig();
        uiConfig.setDefaultPageSize(pageSize);
        OpikConfiguration config = new OpikConfiguration();
        config.setUiConfig(uiConfig);
        return config;
    }

    @Test
    @DisplayName("returns the configured defaultPageSize value")
    void returnsConfiguredDefaultPageSize() {
        UIConfig uiConfig = new UIConfig();
        uiConfig.setDefaultPageSize(OVERRIDE_PAGE_SIZE);

        OpikConfiguration config = new OpikConfiguration();
        config.setUiConfig(uiConfig);

        UIConfigResource resource = new UIConfigResource(config);

        Response response = resource.getUIConfig();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((UIConfig) response.getEntity()).getDefaultPageSize()).isEqualTo(OVERRIDE_PAGE_SIZE);
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
    @DisplayName("rejects defaultPageSize below 1 or above 1000 and accepts boundary values")
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

            UIConfig lowerBoundary = new UIConfig();
            lowerBoundary.setDefaultPageSize(1);
            assertThat(validator.validate(lowerBoundary)).isEmpty();

            UIConfig upperBoundary = new UIConfig();
            upperBoundary.setDefaultPageSize(1000);
            assertThat(validator.validate(upperBoundary)).isEmpty();

            UIConfig inRange = new UIConfig();
            inRange.setDefaultPageSize(500);
            assertThat(validator.validate(inRange)).isEmpty();
        }
    }

    @Test
    @DisplayName("HTTP endpoint serializes default_page_size as snake_case on the wire")
    void wireFormatIsSnakeCase() {
        String body = EXT.target("/v1/private/ui-config/")
                .request()
                .get(String.class);

        assertThat(body)
                .as("wire contract: frontend consumes 'default_page_size'")
                .contains("\"default_page_size\"")
                .contains(String.valueOf(OVERRIDE_PAGE_SIZE))
                .doesNotContain("defaultPageSize");
    }
}
