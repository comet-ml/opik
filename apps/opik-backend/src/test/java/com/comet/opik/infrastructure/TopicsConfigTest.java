package com.comet.opik.infrastructure;

import com.comet.opik.TestConfigUtils;
import io.dropwizard.util.Duration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TopicsConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static Stream<Arguments> validAiLabelsSettings() {
        return Stream.of(
                arguments("topics off, ai-labels off", false, false, null),
                arguments("topics on, ai-labels on with a url", true, true, "https://opik-inference.example.com"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validAiLabelsSettings")
    void validWhenOffOrWhenAiLabelsIsOnWithAnInferenceUrl(String description, boolean topicsEnabled,
            boolean aiLabelsEnabled, String url) {
        assertThat(aiLabelsViolations(topicsEnabled, aiLabelsEnabled, url)).isEmpty();
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
                arguments("config-test.yml", change(vectors -> {
                }), List.of()),
                arguments("budget equal to the interval", change(vectors -> {
                    vectors.setTickInterval(Duration.seconds(60));
                    vectors.setTickBudget(Duration.seconds(60));
                }), List.of("tickBudgetValid")),
                arguments("long window shorter than the window", change(vectors -> {
                    vectors.setWindow(Duration.hours(24));
                    vectors.setLongWindow(Duration.hours(23));
                }), List.of("longWindowValid")),
                arguments("long window equal to the window", change(vectors -> {
                    vectors.setWindow(Duration.hours(24));
                    vectors.setLongWindow(Duration.hours(24));
                }), List.of()),
                arguments("embed timeout equal to the budget", change(vectors -> {
                    vectors.setTickBudget(Duration.seconds(10));
                    vectors.setEmbedTimeout(Duration.seconds(10));
                    vectors.getFind().setMaxExecutionTime(Duration.seconds(5));
                }), List.of("embedTimeoutValid")),
                arguments("backfill window of 30 days", change(vectors -> vectors.setBackfillWindow(Duration.days(30))),
                        List.of()),
                arguments("backfill window shorter than the window", change(vectors -> {
                    vectors.setWindow(Duration.hours(24));
                    vectors.setBackfillWindow(Duration.hours(23));
                }), List.of("backfillWindowValid")),
                arguments("find max execution time equal to the budget", change(vectors -> {
                    vectors.setTickBudget(Duration.seconds(40));
                    vectors.getFind().setMaxExecutionTime(Duration.seconds(40));
                }), List.of()),
                arguments("find max execution time longer than the budget", change(vectors -> {
                    vectors.setTickBudget(Duration.seconds(40));
                    vectors.getFind().setMaxExecutionTime(Duration.seconds(41));
                }), List.of("findMaxExecutionTimeValid")));
    }

    private static Consumer<TopicsConfig.VectorsConfig> change(Consumer<TopicsConfig.VectorsConfig> change) {
        return change;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectorsSettings")
    void vectorsCrossFieldRules(String description, Consumer<TopicsConfig.VectorsConfig> change,
            List<String> expectedViolations) {
        var vectors = TestConfigUtils.loadConfigTest().getTopics().getVectors();
        change.accept(vectors);

        var violations = VALIDATOR.validate(vectors).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }

    static Stream<Arguments> nightlySettings() {
        return Stream.of(
                arguments("config-test.yml", nightly(nightly -> {
                }), List.of()),
                arguments("Qwen deadline equal to the project timeout", nightly(nightly -> {
                    nightly.setProjectTimeout(Duration.minutes(2));
                    nightly.setQwenDeadline(Duration.minutes(2));
                }), List.of("qwenDeadlineValid")));
    }

    private static Consumer<TopicsConfig.NightlyConfig> nightly(Consumer<TopicsConfig.NightlyConfig> change) {
        return change;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nightlySettings")
    void nightlyCrossFieldRules(String description, Consumer<TopicsConfig.NightlyConfig> change,
            List<String> expectedViolations) {
        var nightly = TestConfigUtils.loadConfigTest().getTopics().getNightly();
        change.accept(nightly);

        var violations = VALIDATOR.validate(nightly).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
