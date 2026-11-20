package com.comet.opik.infrastructure;

import com.comet.opik.TestConfigUtils;
import io.dropwizard.util.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TopicsConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static Stream<Arguments> buildSettings() {
        return Stream.of(
                arguments("config-test.yml", (Consumer<TopicsConfig>) topics -> {
                }, List.of()),
                arguments("Qwen deadline equal to the project timeout", (Consumer<TopicsConfig>) topics -> {
                    topics.getBuild().setProjectTimeout(Duration.minutes(2));
                    topics.getBuild().setQwenDeadline(Duration.minutes(2));
                }, List.of("build.qwenDeadlineValid")),
                arguments("naming deadline equal to the project timeout", (Consumer<TopicsConfig>) topics -> {
                    topics.getBuild().setProjectTimeout(Duration.minutes(2));
                    topics.getBuild().setQwenDeadline(Duration.minutes(1));
                    topics.getBuild().setNamingDeadline(Duration.minutes(2));
                }, List.of("build.namingDeadlineValid")),
                arguments("verify deadline equal to the project timeout", (Consumer<TopicsConfig>) topics -> {
                    topics.getBuild().setProjectTimeout(Duration.minutes(2));
                    topics.getBuild().setQwenDeadline(Duration.minutes(1));
                    topics.getBuild().setVerifyDeadline(Duration.minutes(2));
                }, List.of("build.verifyDeadlineValid")),
                arguments("max vectors under the min vectors", (Consumer<TopicsConfig>) topics -> {
                    topics.getBuild().setMinVectors(500);
                    topics.getBuild().setMaxVectors(400);
                }, List.of("build.maxVectorsValid")),
                arguments("max vectors equal to the min vectors", (Consumer<TopicsConfig>) topics -> {
                    topics.getBuild().setMinVectors(500);
                    topics.getBuild().setMaxVectors(500);
                }, List.of()),
                arguments("first-build budget equal to its interval", (Consumer<TopicsConfig>) topics -> {
                    topics.getFirstBuild().setInterval(Duration.minutes(10));
                    topics.getFirstBuild().setBudget(Duration.minutes(10));
                }, List.of("firstBuild.budgetValid")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buildSettings")
    void buildCrossFieldRules(String description, Consumer<TopicsConfig> change, List<String> expectedViolations) {
        var topics = TestConfigUtils.loadConfigTest().getTopics();
        change.accept(topics);

        var violations = VALIDATOR.validate(topics).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .filter(path -> path.startsWith("build.") || path.startsWith("firstBuild."))
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }

    static Stream<Arguments> arrivalTimeouts() {
        return Stream.of(
                arguments("config-test.yml", Duration.seconds(5), List.of()),
                arguments("1s", Duration.seconds(1), List.of()),
                arguments("60s", Duration.seconds(60), List.of()),
                arguments("under 1s", Duration.milliseconds(999), List.of("arrival.timeout")),
                arguments("over 60s", Duration.seconds(61), List.of("arrival.timeout")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("arrivalTimeouts")
    void arrivalTimeoutBounds(String description, Duration timeout, List<String> expectedViolations) {
        var topics = TestConfigUtils.loadConfigTest().getTopics();
        assertThat(topics.getArrival().isEnabled()).isTrue();
        topics.getArrival().setTimeout(timeout);

        var violations = VALIDATOR.validate(topics).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .filter(path -> path.startsWith("arrival."))
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
