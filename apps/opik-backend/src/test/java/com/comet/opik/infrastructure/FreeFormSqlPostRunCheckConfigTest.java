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

    // Dropwizard's validator registers the @MinDuration/@MaxDuration constraint validators.
    private final Validator validator = Validators.newValidator();

    /** The values config-test.yml sets, in {@code mode}, for tests that build the service without the app. */
    public static FreeFormSqlPostRunCheckConfig config(FreeFormSqlPostRunCheckConfig.Mode mode) {
        var config = new FreeFormSqlPostRunCheckConfig();
        config.setMode(mode);
        config.setLogReadDelay(Duration.seconds(10));
        config.setMaxLogReadAttempts(3);
        return config;
    }

    public static FreeFormSqlPostRunCheckConfig config() {
        return config(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
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
    @DisplayName("config.yml ships audit mode with a delay past the natural flush, and is valid")
    void shippedDefaults() throws Exception {
        var config = shipped(Map.of());
        assertThat(config.getMode()).isEqualTo(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        assertThat(config.getLogReadDelay()).isEqualTo(Duration.seconds(10));
        assertThat(config.getMaxLogReadAttempts()).isEqualTo(3);
        assertThat(validator.validate(config)).isEmpty();
    }

    @Test
    @DisplayName("each environment variable overrides its setting")
    void environmentOverrides() throws Exception {
        var config = shipped(Map.of(
                "FREE_FORM_SQL_POST_RUN_CHECK_MODE", "enforce",
                "FREE_FORM_SQL_POST_RUN_CHECK_LOG_READ_DELAY", "8s",
                "FREE_FORM_SQL_POST_RUN_CHECK_MAX_LOG_READ_ATTEMPTS", "5"));
        assertThat(config.getMode()).isEqualTo(FreeFormSqlPostRunCheckConfig.Mode.ENFORCE);
        assertThat(config.getLogReadDelay()).isEqualTo(Duration.seconds(8));
        assertThat(config.getMaxLogReadAttempts()).isEqualTo(5);
        assertThat(shipped(Map.of("FREE_FORM_SQL_POST_RUN_CHECK_MODE", "off")).getMode())
                .isEqualTo(FreeFormSqlPostRunCheckConfig.Mode.OFF);
    }

    @Test
    @DisplayName("an absent setting is rejected: config.yml holds the only defaults")
    void absentSettingsAreRejected() {
        assertThat(validator.validate(new FreeFormSqlPostRunCheckConfig()))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("mode", "logReadDelay", "maxLogReadAttempts");
    }

    static Stream<Arguments> bounds() {
        return Stream.of(
                arguments("logReadDelay 0ms", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setLogReadDelay(Duration.milliseconds(0)), List.of()),
                arguments("logReadDelay 60s", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setLogReadDelay(Duration.seconds(60)), List.of()),
                arguments("logReadDelay 61s", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setLogReadDelay(Duration.seconds(61)), List.of("logReadDelay")),
                arguments("maxLogReadAttempts 0", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMaxLogReadAttempts(0), List.of("maxLogReadAttempts")),
                arguments("maxLogReadAttempts 1", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMaxLogReadAttempts(1), List.of()),
                arguments("maxLogReadAttempts 10", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMaxLogReadAttempts(10), List.of()),
                arguments("maxLogReadAttempts 11", (Consumer<FreeFormSqlPostRunCheckConfig>) c -> c
                        .setMaxLogReadAttempts(11), List.of("maxLogReadAttempts")));
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
