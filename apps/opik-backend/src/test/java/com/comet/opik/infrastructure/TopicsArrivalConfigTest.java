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

    static Stream<Arguments> minStepTimes() {
        return Stream.of(
                arguments("below the timeout", Duration.seconds(1), Duration.seconds(5), List.of()),
                arguments("equal to the timeout", Duration.seconds(5), Duration.seconds(5), List.of()),
                arguments("above the timeout", Duration.seconds(6), Duration.seconds(5),
                        List.of("minStepTimeValid")));
    }

    @ParameterizedTest(name = "topics.arrival.minStepTime {0}")
    @MethodSource("minStepTimes")
    void minStepTimeNotAboveTimeout(String description, Duration minStepTime, Duration timeout,
            List<String> expectedViolations) {
        var arrival = TestConfigUtils.loadConfigTest().getTopics().getArrival();
        arrival.setMinStepTime(minStepTime);
        arrival.setTimeout(timeout);

        var violations = VALIDATOR.validate(arrival).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
