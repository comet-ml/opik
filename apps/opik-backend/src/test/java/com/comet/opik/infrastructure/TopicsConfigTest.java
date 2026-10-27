package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TopicsConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validWhenOffOrWhenAiLabelsIsOnWithAnInferenceUrl() {
        assertThat(aiLabelsViolations(false, false, null)).isEmpty();
        assertThat(aiLabelsViolations(true, true, "https://opik-inference.example.com")).isEmpty();
    }

    static Stream<Arguments> invalidAiLabelsSettings() {
        return Stream.of(
                arguments("topics on, ai-labels off", true, false, "https://opik-inference.example.com"),
                arguments("topics on, ai-labels on without a url", true, true, " "),
                arguments("topics on, ai-labels on with no url", true, true, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAiLabelsSettings")
    void enabledRequiresAiLabelsAndItsInferenceUrl(String description, boolean topicsEnabled,
            boolean aiLabelsEnabled, String url) {
        assertThat(aiLabelsViolations(topicsEnabled, aiLabelsEnabled, url)).singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .contains("aiLabels.enabled").contains("aiLabels.inference.url"));
    }

    private static Set<ConstraintViolation<OpikConfiguration>> aiLabelsViolations(boolean topicsEnabled,
            boolean aiLabelsEnabled, String url) {
        var config = new OpikConfiguration();
        config.getTopics().setEnabled(topicsEnabled);
        config.getAiLabels().setEnabled(aiLabelsEnabled);
        config.getAiLabels().getInference().setUrl(url);
        return VALIDATOR.validateProperty(config, "topicsAiLabelsEnabled");
    }

    static Stream<Arguments> vectorsSettings() {
        return Stream.of(
                arguments("defaults", null, null, null, null, null, null, null, List.of()),
                arguments("budget equal to the interval", Duration.seconds(60), Duration.seconds(60), null, null,
                        null, null, null, List.of("tickBudgetValid")),
                arguments("long window shorter than the window", null, null, Duration.hours(24), Duration.hours(23),
                        null, null, null, List.of("longWindowValid")),
                arguments("long window equal to the window", null, null, Duration.hours(24), Duration.hours(24),
                        null, null, null, List.of()),
                arguments("embed batch above the service's 256", null, null, null, null, 257, null, null,
                        List.of("embedBatchSize")),
                arguments("embed timeout equal to the budget", null, Duration.seconds(10), null, null, null,
                        Duration.seconds(10), null, List.of("embedTimeoutValid")),
                arguments("backfill window of 30 days", null, null, null, null, null, null, Duration.days(30),
                        List.of()),
                arguments("backfill window shorter than the window", null, null, Duration.hours(24), null, null,
                        null, Duration.hours(23), List.of("backfillWindowValid")),
                arguments("backfill window above 365 days", null, null, null, null, null, null, Duration.days(366),
                        List.of("backfillWindow")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectorsSettings")
    void vectorsRules(String description, Duration tickInterval, Duration tickBudget, Duration window,
            Duration longWindow, Integer embedBatchSize, Duration embedTimeout, Duration backfillWindow,
            List<String> expectedViolations) {
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
        if (embedTimeout != null) {
            vectors.setEmbedTimeout(embedTimeout);
        }
        if (backfillWindow != null) {
            vectors.setBackfillWindow(backfillWindow);
        }

        var violations = VALIDATOR.validate(vectors).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }

    @Test
    void defaultsOfTheWindowReadGuards() {
        var find = new TopicsConfig().getVectors().getFind();

        assertThat(find.getMaxRowsToRead()).isEqualTo(30_000_000L);
        assertThat(find.getMaxMemoryUsage()).isEqualTo(1024L * 1024 * 1024);
        assertThat(find.getMaxExecutionTime()).isEqualTo(Duration.seconds(30));
        assertThat(find.getCreatedAtSlack()).isEqualTo(Duration.days(1));
        assertThat(new TopicsConfig().getVectors().getBackfillWindow().toMilliseconds()).isZero();
    }
}
