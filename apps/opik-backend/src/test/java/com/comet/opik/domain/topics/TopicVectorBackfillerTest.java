package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProjectDAO.Backfill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("topic vectors backfiller")
class TopicVectorBackfillerTest {

    private final Instant now = TopicsTestClock.randomInstant();

    @Test
    @DisplayName("projects that drain in one read go first, smallest first, then the large ones, the most recently "
            + "active first among equals")
    void order() {
        int cap = 100;
        var small = backfill(10, now);
        var smallerOlder = backfill(5, now.minusSeconds(60));
        var largeRecent = backfill(500, now);
        var largeOlder = backfill(10_000, now.minusSeconds(60));
        var sameAsSmallOlder = backfill(10, now.minusSeconds(60));

        var ordered = List.of(largeOlder, small, sameAsSmallOlder, largeRecent, smallerOlder).stream()
                .sorted(TopicVectorBackfiller.order(cap)).toList();

        assertThat(ordered).containsExactly(smallerOlder, small, sameAsSmallOlder, largeRecent, largeOlder);
    }

    private static Backfill backfill(long remaining, Instant lastDoneAt) {
        return Backfill.builder()
                .workspaceId(UUID.randomUUID().toString())
                .projectId(UUID.randomUUID())
                .backfillStartedAt(lastDoneAt)
                .backfillLastDoneAt(lastDoneAt)
                .backfillTracesTotal(remaining)
                .build();
    }
}
