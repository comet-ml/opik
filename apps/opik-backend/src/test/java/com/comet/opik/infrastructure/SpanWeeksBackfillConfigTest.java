package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SpanWeeksBackfillConfigTest {

    private static final String INTERVAL_MESSAGE = "spanWeeksBackfill.interval must exceed spanWeeksBackfill.queryTimeout";

    private final Validator validator = Validators.newValidator();

    static Stream<Arguments> intervalAgainstQueryTimeout() {
        // In seconds, so the interval stays within its 10s-1h bounds and the timeout within its 1s-30m bounds.
        long queryTimeout = RandomUtils.secure().randomLong(60, 600);
        return Stream.of(
                arguments(queryTimeout - 1, queryTimeout, List.of(INTERVAL_MESSAGE)),
                arguments(queryTimeout, queryTimeout, List.of(INTERVAL_MESSAGE)),
                arguments(queryTimeout + 1, queryTimeout, List.of()));
    }

    @ParameterizedTest(name = "interval {0}s, queryTimeout {1}s")
    @MethodSource
    void intervalAgainstQueryTimeout(long intervalSeconds, long queryTimeoutSeconds, List<String> expected) {
        var config = SpanWeeksBackfillConfig.builder()
                .interval(Duration.seconds(intervalSeconds))
                .weeksPerChunk(RandomUtils.secure().randomInt(1, 53))
                .maxThreads(RandomUtils.secure().randomInt(1, 17))
                .queryTimeout(Duration.seconds(queryTimeoutSeconds))
                .lockWaitTime(Duration.seconds(RandomUtils.secure().randomInt(0, 61)))
                .build();

        var actual = validator.validate(config).stream().map(ConstraintViolation::getMessage).toList();

        assertThat(actual).isEqualTo(expected);
    }
}
