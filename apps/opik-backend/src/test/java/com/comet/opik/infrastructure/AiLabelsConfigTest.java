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
