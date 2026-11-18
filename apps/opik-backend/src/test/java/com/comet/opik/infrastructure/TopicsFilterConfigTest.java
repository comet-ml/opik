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

class TopicsFilterConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    static Stream<Arguments> maxExecutionTimes() {
        return Stream.of(
                arguments(Duration.seconds(1), List.of()),
                arguments(Duration.minutes(10), List.of()),
                arguments(Duration.milliseconds(1500), List.of("maxExecutionTimeWholeSeconds")),
                arguments(Duration.milliseconds(999), List.of("maxExecutionTime", "maxExecutionTimeWholeSeconds")),
                arguments(Duration.seconds(601), List.of("maxExecutionTime")));
    }

    /** A sub-second part would be cut by {@code toSeconds()}, and ClickHouse would get another limit. */
    @ParameterizedTest(name = "topics.filter.maxExecutionTime {0}: {1}")
    @MethodSource("maxExecutionTimes")
    void maxExecutionTimeIsWholeSecondsFromOneSecondToTenMinutes(Duration maxExecutionTime,
            List<String> expectedViolations) {
        var filter = TestConfigUtils.loadConfigTest().getTopics().getFilter();
        filter.setMaxExecutionTime(maxExecutionTime);

        var violations = VALIDATOR.validate(filter).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
