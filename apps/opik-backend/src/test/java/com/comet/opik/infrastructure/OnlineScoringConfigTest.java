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

class OnlineScoringConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String TTL_LONG_ENOUGH = "scoringStatusTtlLongEnough";

    static Stream<Arguments> scoringStatusTtls() {
        return Stream.of(
                arguments("twice the default pending duration", Duration.minutes(20), null, true),
                arguments("under twice the default pending duration", Duration.minutes(19), null, false),
                arguments("twice a stream's own pending duration", Duration.hours(2), Duration.hours(1), true),
                arguments("under twice a stream's own pending duration", Duration.hours(1), Duration.minutes(45),
                        false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scoringStatusTtls")
    void theScoringStatusTtlOutlastsTheWaitForEveryStreamsNextDelivery(String description, Duration ttl,
            Duration streamPendingDuration, boolean expectedValid) {
        var config = OnlineScoringConfig.builder()
                .scoringStatusTtl(ttl)
                .pendingMessageDuration(Duration.minutes(10))
                .streams(List.of(
                        OnlineScoringConfig.StreamConfiguration.builder().build(),
                        OnlineScoringConfig.StreamConfiguration.builder()
                                .pendingMessageDuration(streamPendingDuration)
                                .build()))
                .build();

        assertThat(VALIDATOR.validateProperty(config, TTL_LONG_ENOUGH).isEmpty()).isEqualTo(expectedValid);
    }
}
