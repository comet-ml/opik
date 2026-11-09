package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AiLabelsConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String URL = "https://opik-inference.example.com";
    private static final String CREDENTIAL = "credential";

    static Stream<Arguments> inferenceSettings() {
        return Stream.of(
                arguments("disabled, nothing set", false, true, null, null, List.of()),
                // The block itself is always required; config.yml always provides it.
                arguments("disabled, no inference block", false, false, null, null, List.of("inference")),
                arguments("enabled, blank url", true, true, " ", CREDENTIAL, List.of("inferenceConfigured")),
                arguments("enabled, no credential", true, true, URL, null, List.of("inferenceConfigured")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inferenceSettings")
    void inferenceBlockIsAlwaysRequiredAndItsSettingsWhenEnabled(String description, boolean enabled,
            boolean inferenceBlock, String url,
            String credential, List<String> expectedViolations) {
        var config = new AiLabelsConfig();
        config.setEnabled(enabled);
        if (inferenceBlock) {
            config.getInference().setUrl(url);
            config.getInference().setCredential(credential);
        } else {
            config.setInference(null);
        }

        var violations = VALIDATOR.validate(config).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }

    static Stream<Arguments> inferenceUrls() {
        return Stream.of(
                arguments("unset", true, null, true),
                arguments("blank", true, " ", true),
                arguments("https", true, URL, true),
                arguments("https, upper case scheme, with a port and a path", true, "HTTPS://opik-inference:8443/base",
                        true),
                arguments("http to localhost", true, "http://localhost:8080", true),
                arguments("http to 127.0.0.1", true, "http://127.0.0.1:8080", true),
                arguments("http to [::1]", true, "http://[::1]:8080", true),
                arguments("http to a cluster service", true, "http://opik-inference.inference.svc:8000", false),
                arguments("http to a private address", true, "http://10.0.0.12:8000", false),
                arguments("http to a name that starts with localhost", true, "http://localhost.example.com", false),
                arguments("another scheme", true, "ftp://opik-inference.example.com", false),
                arguments("no host", true, "https:///v1", false),
                arguments("not a URL", true, "https://opik inference", false),
                // Off, a set URL is still checked: the guardrails decide calls use it under their own toggle.
                arguments("off, unset", false, null, true),
                arguments("off, https", false, URL, true),
                arguments("off, http to localhost", false, "http://localhost:8080", true),
                arguments("off, http to a cluster service", false, "http://opik-inference.inference.svc:8000", false),
                arguments("off, not a URL", false, "https://opik inference", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inferenceUrls")
    void inferenceUrlIsHttpsOrHttpToLocalhostWheneverSet(String description, boolean enabled, String url,
            boolean valid) {
        var config = new AiLabelsConfig();
        config.setEnabled(enabled);
        config.getInference().setUrl(url);

        var violations = VALIDATOR.validateProperty(config, "inferenceUrlSecure").stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).isEqualTo(valid ? List.of() : List.of("inferenceUrlSecure"));
    }

    @Test
    void enabledRequiresTraceColumnsNonNullable() {
        var config = new OpikConfiguration();
        config.getAiLabels().setEnabled(true);

        assertThat(VALIDATOR.validateProperty(config, "aiLabelsDataModelSupported")).singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .contains("databaseAnalyticsDataModel.traceColumnsNonNullable"));

        config.setDatabaseAnalyticsDataModel(config.getDatabaseAnalyticsDataModel().toBuilder()
                .traceColumnsNonNullable(true)
                .build());

        assertThat(VALIDATOR.validateProperty(config, "aiLabelsDataModelSupported")).isEmpty();
    }

    static Stream<Arguments> decideConnectionSettings() {
        return Stream.of(
                arguments("the defaults", Duration.seconds(30), Duration.minutes(5), List.of()),
                arguments("no keep-alive", Duration.milliseconds(0), Duration.minutes(5), List.of()),
                arguments("no keep-alive set", null, Duration.minutes(5), List.of("inference.decideKeepAlive")),
                arguments("a time to live under a second", Duration.seconds(30), Duration.milliseconds(999),
                        List.of("inference.decideTimeToLive")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decideConnectionSettings")
    void decideConnectionSettingsAreValidated(String description, Duration decideKeepAlive,
            Duration decideTimeToLive, List<String> expectedViolations) {
        var config = new AiLabelsConfig();
        config.getInference().setDecideKeepAlive(decideKeepAlive);
        config.getInference().setDecideTimeToLive(decideTimeToLive);

        var violations = VALIDATOR.validate(config).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }

    static Stream<Arguments> renderMaxExecutionTimes() {
        return Stream.of(
                arguments(Duration.seconds(1), List.of()),
                arguments(Duration.seconds(25), List.of()),
                arguments(Duration.seconds(26), List.of("renderMaxExecutionTimeValid")),
                arguments(Duration.milliseconds(999), List.of("renderMaxExecutionTime")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("renderMaxExecutionTimes")
    void renderMaxExecutionTimeIsAtLeastASecondAndAtMostTheTickBudget(Duration renderMaxExecutionTime,
            List<String> expectedViolations) {
        var config = new AiLabelsConfig();
        config.setTickBudget(Duration.seconds(25));
        config.setRenderMaxExecutionTime(renderMaxExecutionTime);

        var violations = VALIDATOR.validate(config).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
