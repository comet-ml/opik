package com.comet.opik;

import com.comet.opik.infrastructure.OpikConfiguration;
import io.dropwizard.configuration.ConfigurationException;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import lombok.experimental.UtilityClass;

import java.io.IOException;

/**
 * Loads the test {@link OpikConfiguration} from {@code config-test.yml} — the same file the test app
 * boots with — so tests reading individual config sections stay in sync with the running app instead
 * of hardcoding values that can drift.
 */
@UtilityClass
public class TestConfigUtils {

    public static final String CONFIG_TEST_YML_PATH = "src/test/resources/config-test.yml";

    public OpikConfiguration loadConfigTest() {
        try {
            return new YamlConfigurationFactory<>(
                    OpikConfiguration.class,
                    Validators.newValidator(),
                    Jackson.newObjectMapper(),
                    "dw")
                    .build(new FileConfigurationSourceProvider(), CONFIG_TEST_YML_PATH);
        } catch (ConfigurationException | IOException exception) {
            throw new IllegalStateException("Failed to load '%s'".formatted(CONFIG_TEST_YML_PATH), exception);
        }
    }
}
