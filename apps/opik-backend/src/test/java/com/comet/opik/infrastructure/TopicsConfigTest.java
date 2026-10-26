package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TopicsConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static Stream<Arguments> aiLabelsSettings() {
        return Stream.of(
                arguments("topics off, ai-labels off", false, false, null, true),
                arguments("topics on, ai-labels off", true, false, "https://opik-inference.example.com", false),
                arguments("topics on, ai-labels on without a url", true, true, " ", false),
                arguments("topics on, ai-labels on with a url", true, true, "https://opik-inference.example.com",
                        true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aiLabelsSettings")
    void enabledRequiresAiLabelsAndItsInferenceUrl(String description, boolean topicsEnabled,
            boolean aiLabelsEnabled, String url, boolean valid) {
        var config = new OpikConfiguration();
        config.getTopics().setEnabled(topicsEnabled);
        config.getAiLabels().setEnabled(aiLabelsEnabled);
        config.getAiLabels().getInference().setUrl(url);

        var violations = VALIDATOR.validateProperty(config, "topicsAiLabelsEnabled");

        if (valid) {
            assertThat(violations).isEmpty();
        } else {
            assertThat(violations).singleElement().satisfies(violation -> assertThat(violation.getMessage())
                    .contains("aiLabels.enabled").contains("aiLabels.inference.url"));
        }
    }

    static Stream<Arguments> vectorsSettings() {
        return Stream.of(
                arguments("defaults", null, null, null, null, null, List.of()),
                arguments("budget equal to the interval", Duration.seconds(60), Duration.seconds(60), null, null,
                        null, List.of("tickBudgetValid")),
                arguments("long window shorter than the window", null, null, Duration.hours(24), Duration.hours(23),
                        null, List.of("longWindowValid")),
                arguments("long window equal to the window", null, null, Duration.hours(24), Duration.hours(24),
                        null, List.of()),
                arguments("embed batch above the service's 256", null, null, null, null, 257,
                        List.of("embedBatchSize")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectorsSettings")
    void vectorsRules(String description, Duration tickInterval, Duration tickBudget, Duration window,
            Duration longWindow, Integer embedBatchSize, List<String> expectedViolations) {
        var vectors = new TopicsConfig().getVectors();
        if (tickInterval != null) {
            vectors.setTickInterval(tickInterval);
        }
        if (tickBudget != null) {
            vectors.setTickBudget(tickBudget);
        }
        if (window != null) {
            vectors.setWindow(window);
        }
        if (longWindow != null) {
            vectors.setLongWindow(longWindow);
        }
        if (embedBatchSize != null) {
            vectors.setEmbedBatchSize(embedBatchSize);
        }

        var violations = VALIDATOR.validate(vectors).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
