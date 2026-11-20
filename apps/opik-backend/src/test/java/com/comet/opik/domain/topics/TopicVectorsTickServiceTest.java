package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.domain.topics.TopicVectorsTickService.VectorKey;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("topic vectors tick, without containers")
class TopicVectorsTickServiceTest {

    private static final Instant NOW = TopicsTestClock.randomInstant();
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

    @Test
    @DisplayName("a workspace's read starts the overlap before its watermark, never before the window, and at the "
            + "window without one")
    void readFrom() {
        var windowStart = NOW.minus(Duration.ofHours(24));
        var recent = NOW.minus(Duration.ofMinutes(1));

        assertThat(TopicVectorsTickService.readFrom(windowStart, null)).isEqualTo(windowStart);
        assertThat(TopicVectorsTickService.readFrom(windowStart, recent))
                .isEqualTo(recent.minus(TopicVectorsTickService.WATERMARK_OVERLAP));
        assertThat(TopicVectorsTickService.readFrom(windowStart, windowStart.minus(Duration.ofDays(1))))
                .isEqualTo(windowStart);
    }

    @Test
    @DisplayName("the watermark stops at the first trace found and not written, else moves to the last status of a "
            + "full read, else to the read's upper bound")
    void watermark() {
        var found = IntStream.range(0, 4).mapToObj(i -> candidate(NOW.minusSeconds(40 - i))).toList();
        var all = Set.copyOf(found.stream().map(VectorKey::of).toList());

        // The second one was left: an embed or insert failure, or the budget.
        var withoutSecond = Set.of(VectorKey.of(found.get(0)), VectorKey.of(found.get(2)),
                VectorKey.of(found.get(3)));
        assertThat(TopicVectorsTickService.watermark(found, withoutSecond, 10, NOW))
                .isEqualTo(found.get(1).statusUpdatedAt());
        // The same trace in another project or version is another row.
        var sameTraceElsewhere = new Candidate(found.get(1).workspaceId(), UUID.randomUUID(), found.get(1).traceId(),
                found.get(1).traceCreatedAt(), 1, found.get(1).statusUpdatedAt(), "summary");
        assertThat(TopicVectorsTickService.watermark(List.of(sameTraceElsewhere), all, 10, NOW))
                .isEqualTo(sameTraceElsewhere.statusUpdatedAt());
        assertThat(TopicVectorsTickService.watermark(found, all, 4, NOW)).isEqualTo(found.get(3).statusUpdatedAt());
        assertThat(TopicVectorsTickService.watermark(found, all, 10, NOW)).isEqualTo(NOW);
        assertThat(TopicVectorsTickService.watermark(List.of(), Set.of(), 10, NOW)).isEqualTo(NOW);
    }

    private static Candidate candidate(Instant statusUpdatedAt) {
        return new Candidate("workspace", UUID.randomUUID(), UUID.randomUUID(), NOW.minus(Duration.ofHours(1)), 1,
                statusUpdatedAt, "summary");
    }
}
