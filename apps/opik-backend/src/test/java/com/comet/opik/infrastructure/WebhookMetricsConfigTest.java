package com.comet.opik.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.util.Duration;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped config.yml the way OpikApplication does, so the defaults this file actually ships are
 * covered rather than only the values a unit test hands the job directly.
 */
@DisplayName("Webhook Metrics Config Binding Test")
class WebhookMetricsConfigTest {

    private static final Path CONFIG = Path.of("config.yml");

    private OpikConfiguration load(Map<String, String> environment) throws Exception {
        assertThat(Files.exists(CONFIG)).as("config.yml must resolve from the module root").isTrue();

        // Mirrors OpikApplication's EnvironmentVariableSubstitutor, but reading a map rather than the real
        // environment, so the result does not depend on what happens to be exported on the machine.
        var substitutor = new StringSubstitutor(StringLookupFactory.INSTANCE.mapStringLookup(environment));
        substitutor.setValueDelimiter(":-");
        substitutor.setEnableUndefinedVariableException(false);

        ObjectMapper mapper = Jackson.newObjectMapper();
        // No validator: this asserts what the YAML binds to, not whether every unrelated section is valid
        // without its own environment.
        var factory = new YamlConfigurationFactory<>(OpikConfiguration.class, null, mapper, "dw");

        return factory.build(new SubstitutingSourceProvider(new FileConfigurationSourceProvider(), substitutor),
                CONFIG.toString());
    }

    @Test
    @DisplayName("the shipped default alert window is 24h")
    void shippedDefaultAlertWindowIsTwentyFourHours() throws Exception {
        var config = load(Map.of());

        assertThat(config.getWebhook().getMetrics().getDefaultAlertWindow())
                .as("a threshold config persisted without a window evaluates over this period, so it must "
                        + "match the alerts form's own default rather than drift from it")
                .isEqualTo(Duration.hours(24));
    }

    @Test
    @DisplayName("the default alert window can be overridden from the environment")
    void defaultAlertWindowIsOverridable() throws Exception {
        var config = load(Map.of("WEBHOOK_METRICS_DEFAULT_ALERT_WINDOW", "6h"));

        assertThat(config.getWebhook().getMetrics().getDefaultAlertWindow()).isEqualTo(Duration.hours(6));
    }
}
