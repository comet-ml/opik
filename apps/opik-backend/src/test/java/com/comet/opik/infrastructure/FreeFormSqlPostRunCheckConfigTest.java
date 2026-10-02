package com.comet.opik.infrastructure;

import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.Validator;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Free-form SQL post-run check config")
public class FreeFormSqlPostRunCheckConfigTest {

    // Dropwizard's validator registers the @MinDuration constraint validator.
    private final Validator validator = Validators.newValidator();

    /** The values config-test.yml sets, for tests that build the reader without the app. */
    public static FreeFormSqlPostRunCheckConfig config() {
        var config = new FreeFormSqlPostRunCheckConfig();
        config.setMinFlushInterval(Duration.seconds(1));
        config.setLogRetryDelay(Duration.milliseconds(500));
        config.setMaxFlushAttempts(3);
        return config;
    }

    /** Binds the shipped config.yml with OpikApplication's env-var substitution, reading {@code environment}. */
    private static FreeFormSqlPostRunCheckConfig shipped(Map<String, String> environment) throws Exception {
        var substitutor = new StringSubstitutor(StringLookupFactory.INSTANCE.mapStringLookup(environment));
        substitutor.setValueDelimiter(":-");
        substitutor.setEnableUndefinedVariableException(false);
        var factory = new YamlConfigurationFactory<>(OpikConfiguration.class, null, Jackson.newObjectMapper(), "dw");
        return factory.build(new SubstitutingSourceProvider(new FileConfigurationSourceProvider(), substitutor),
                Path.of("config.yml").toString()).getFreeFormSqlPostRunCheck();
    }

    @Test
    @DisplayName("config.yml ships the defaults, and they are valid")
    void shippedDefaults() throws Exception {
        var config = shipped(Map.of());
        assertThat(config.getMinFlushInterval()).isEqualTo(Duration.seconds(1));
        assertThat(config.getLogRetryDelay()).isEqualTo(Duration.milliseconds(500));
        assertThat(config.getMaxFlushAttempts()).isEqualTo(3);
        assertThat(validator.validate(config)).isEmpty();
    }

    @Test
    @DisplayName("each environment variable overrides its setting")
    void environmentOverrides() throws Exception {
        var config = shipped(Map.of(
                "FREE_FORM_SQL_POST_RUN_CHECK_MIN_FLUSH_INTERVAL", "2s",
                "FREE_FORM_SQL_POST_RUN_CHECK_LOG_RETRY_DELAY", "250ms",
                "FREE_FORM_SQL_POST_RUN_CHECK_MAX_FLUSH_ATTEMPTS", "5"));
        assertThat(config.getMinFlushInterval()).isEqualTo(Duration.seconds(2));
        assertThat(config.getLogRetryDelay()).isEqualTo(Duration.milliseconds(250));
        assertThat(config.getMaxFlushAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("an absent setting is rejected: config.yml holds the only defaults")
    void absentSettingsAreRejected() {
        assertThat(validator.validate(new FreeFormSqlPostRunCheckConfig()))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("minFlushInterval", "logRetryDelay", "maxFlushAttempts");
    }

    static Stream<Arguments> bounds() {
        return Stream.of(
                // Whole seconds from 1s to 60s: the cluster-wide permit is a Redis rate limiter counted in seconds.
                arguments("minFlushInterval 999ms", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMinFlushInterval(Duration.milliseconds(999)),
                        List.of("minFlushInterval", "minFlushIntervalInWholeSeconds")),
                arguments("minFlushInterval 1s", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMinFlushInterval(Duration.seconds(1)), List.of()),
                arguments("minFlushInterval 1.5s", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMinFlushInterval(Duration.milliseconds(1_500)), List.of("minFlushIntervalInWholeSeconds")),
                arguments("minFlushInterval 60s", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMinFlushInterval(Duration.seconds(60)), List.of()),
                arguments("minFlushInterval 61s", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMinFlushInterval(Duration.seconds(61)), List.of("minFlushInterval")),
                arguments("logRetryDelay 0ms", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setLogRetryDelay(Duration.milliseconds(0)), List.of()),
                arguments("maxFlushAttempts 0", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMaxFlushAttempts(0), List.of("maxFlushAttempts")),
                arguments("maxFlushAttempts 1", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMaxFlushAttempts(1), List.of()),
                arguments("maxFlushAttempts 10", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMaxFlushAttempts(10), List.of()),
                arguments("maxFlushAttempts 11", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMaxFlushAttempts(11), List.of("maxFlushAttempts")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bounds")
    @DisplayName("each setting is checked at its limits")
    void bounds(String name, Consumer<FreeFormSqlPostRunCheckConfig> change, List<String> expectedViolations) {
        var config = config();
        change.accept(config);
        assertThat(validator.validate(config)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
