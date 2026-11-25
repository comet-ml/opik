package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("topics discovery candidates")
class TopicsDiscoveryCandidatesTest {

    static Stream<Arguments> roundRobin() {
        return Stream.of(
                // The candidates: one project of each workspace in turn.
                Arguments.of(List.of(List.of("a1", "a2", "a3"), List.of("b1"), List.of("c1", "c2")),
                        List.of("a1", "b1", "c1", "a2", "c2", "a3")),
                // The planner: one labelling pass, then one discovery run, and the longer list goes on alone.
                Arguments.of(List.of(List.of("l1"), List.of("d1", "d2", "d3")), List.of("l1", "d1", "d2", "d3")),
                Arguments.of(List.of(List.of("l1", "l2"), List.of()), List.of("l1", "l2")),
                Arguments.of(List.of(), List.of()));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("round robin takes one item of each list in turn, in the lists' order")
    void roundRobin(List<List<String>> lists, List<String> expected) {
        assertThat(TopicsDiscoveryCandidates.roundRobin(lists)).isEqualTo(expected);
    }
}
