package com.comet.opik.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped config.yml the way OpikApplication does, so the defaults this file actually ships are covered
 * rather than only the values a unit test hands the factory directly.
 */
@DisplayName("Database Analytics Config Binding Test")
class DatabaseAnalyticsConfigTest {

    private static final Path CONFIG = Path.of("config.yml");

    private DatabaseAnalyticsFactory load(Map<String, String> environment) throws Exception {
        assertThat(Files.exists(CONFIG)).as("config.yml must resolve from the module root").isTrue();

        // Mirrors OpikApplication's EnvironmentVariableSubstitutor, but reading a map rather than the real
        // environment, so the result does not depend on what happens to be exported on the machine.
        var substitutor = new StringSubstitutor(StringLookupFactory.INSTANCE.mapStringLookup(environment));
        substitutor.setValueDelimiter(":-");
        substitutor.setEnableUndefinedVariableException(false);

        ObjectMapper mapper = Jackson.newObjectMapper();
        // No validator: this asserts what the YAML binds to, not whether every unrelated section is valid without its
        // own environment.
        var factory = new YamlConfigurationFactory<>(OpikConfiguration.class, null, mapper, "dw");

        return factory.build(new SubstitutingSourceProvider(new FileConfigurationSourceProvider(), substitutor),
                CONFIG.toString()).getDatabaseAnalytics();
    }

    @Test
    @DisplayName("the shipped default progress-header cadence is 3000ms")
    void shippedDefaultProgressHeaderCadenceIsThreeSeconds() throws Exception {
        var databaseAnalytics = load(Map.of());

        assertThat(databaseAnalytics.getHttpHeadersProgressIntervalMs())
                .as("the shipped default must match the field default in DatabaseAnalyticsFactory, since a deployment "
                        + "inherits whichever of the two reaches it first")
                .isEqualTo(3000);
    }

    @Test
    @DisplayName("the progress-header cadence can be overridden from the environment")
    void progressHeaderCadenceIsOverridable() throws Exception {
        var databaseAnalytics = load(Map.of("ANALYTICS_DB_HTTP_HEADERS_PROGRESS_INTERVAL_MS", "5000"));

        assertThat(databaseAnalytics.getHttpHeadersProgressIntervalMs()).isEqualTo(5000);
    }

    @Test
    @DisplayName("a defined-but-empty environment variable binds to null rather than to the config.yml default")
    void definedButEmptyEnvironmentVariableBindsToNull() throws Exception {
        // Why the docker-compose passthrough carries the literal 3000 instead of ':-' like its siblings: Compose
        // renders ':-' to a defined-but-empty variable, and the substitutor below — OpikApplication's — resolves that
        // to empty rather than falling back to the ':-3000' default. Binding null puts the R2DBC path back on
        // ClickHouse's 100ms cadence, which is OPIK-8628. @NotNull turns that into a startup failure rather than a
        // silent regression, but the compose form has to be right either way.
        var databaseAnalytics = load(Map.of("ANALYTICS_DB_HTTP_HEADERS_PROGRESS_INTERVAL_MS", ""));

        assertThat(databaseAnalytics.getHttpHeadersProgressIntervalMs()).isNull();
    }
}
