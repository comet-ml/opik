package com.comet.opik.infrastructure;

import com.comet.opik.TestConfigUtils;
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

class TopicsArrivalConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static Stream<Arguments> arrivalTimeouts() {
        return Stream.of(
                arguments("config-test.yml", Duration.seconds(5), List.of()),
                arguments("1s", Duration.seconds(1), List.of()),
                arguments("60s", Duration.seconds(60), List.of()),
                arguments("under 1s", Duration.milliseconds(999), List.of("timeout")),
                arguments("over 60s", Duration.seconds(61), List.of("timeout")));
    }

    @ParameterizedTest(name = "topics.arrival.timeout {0}")
    @MethodSource("arrivalTimeouts")
    void arrivalTimeoutBounds(String description, Duration timeout, List<String> expectedViolations) {
        var arrival = TestConfigUtils.loadConfigTest().getTopics().getArrival();
        assertThat(arrival.isEnabled()).isTrue();
        arrival.setTimeout(timeout);

        var violations = VALIDATOR.validate(arrival).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
