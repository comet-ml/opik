package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("topic vectors tick, without containers")
class TopicVectorsTickServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final Instant APPLIES_FROM = Instant.parse("2026-11-01T00:00:00Z");

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

    @Test
    @DisplayName("workspaces are visited from an offset that moves by one every tick, negative sequences included")
    void rotated() {
        assertThat(TopicVectorsTickService.rotated(List.of("a", "b", "c"), 4)).containsExactly("b", "c", "a");
        assertThat(TopicVectorsTickService.rotated(List.of("a", "b", "c"), -1)).containsExactly("c", "a", "b");
        assertThat(TopicVectorsTickService.rotated(List.<String>of(), 7)).isEmpty();
    }
}
