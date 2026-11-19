package com.comet.opik.domain.topics;

import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("topic vectors tick, without containers")
class TopicVectorsTickServiceTest {

    private static final Instant NOW = Instant.ofEpochSecond(RandomUtils.secure().randomLong(0, 4_000_000_000L));
    // Inside the 30-day window and before the 24-hour one.
    private static final Instant APPLIES_FROM = NOW.minus(Duration.ofHours(RandomUtils.secure().randomLong(25, 720)));

    static Stream<Arguments> windows() {
        return Stream.of(
                arguments(Duration.ofHours(24), NOW.minus(Duration.ofHours(24))),
                // A window reaching before applies_from starts at applies_from.
                arguments(Duration.ofDays(30), APPLIES_FROM));
    }

    @ParameterizedTest(name = "window {0} starts at {1}")
    @MethodSource("windows")
    @DisplayName("the window's lower bound is max(applies_from, now - window)")
    void lowerBound(Duration window, Instant expected) {
        assertThat(TopicVectorsTickService.lowerBound(APPLIES_FROM, NOW, window)).isEqualTo(expected);
    }
}
