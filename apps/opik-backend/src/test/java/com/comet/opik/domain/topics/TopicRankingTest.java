package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The one rank rule of a project's live topics, and the counts a saved run writes with it. */
@DisplayName("topics ranking")
class TopicRankingTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final LocalDate BORN = LocalDate.parse("2026-11-01");

    @Test
    @DisplayName("by traces, most first, then the earlier born, then the id as text")
    void order() {
        var largestYoungest = topic("00000000-0000-0000-0000-00000000000c", BORN.plusDays(2), 900);
        var small = topic("00000000-0000-0000-0000-000000000002", BORN, 10);
        var largeYounger = topic("00000000-0000-0000-0000-000000000003", BORN.plusDays(1), 50);
        var largeOlderHigherId = topic("00000000-0000-0000-0000-00000000000b", BORN, 50);
        var largeOlderLowerId = topic("00000000-0000-0000-0000-00000000000a", BORN, 50);

        var ranked = List.of(small, largeYounger, largeOlderHigherId, largeOlderLowerId, largestYoungest).stream()
                .sorted(TopicRanking.order(Topic::size))
                .toList();

        assertThat(ranked).containsExactly(largestYoungest, largeOlderLowerId, largeOlderHigherId, largeYounger,
                small);
    }

    @Test
    @DisplayName("a run's counts: one row per live topic ranked 1..n by size, then Other with no rank")
    void runCounts() {
        var runId = UUID.randomUUID();
        var savedAt = Instant.parse("2026-11-02T02:10:00Z");
        var small = topic("00000000-0000-0000-0000-000000000001", BORN, 10);
        var large = topic("00000000-0000-0000-0000-000000000002", BORN, 70);

        var counts = TopicRanking.runCounts(runId, WORKSPACE_ID, PROJECT_ID, List.of(small, large), 20, savedAt);

        var row = TopicRunCount.builder().runId(runId).workspaceId(WORKSPACE_ID).projectId(PROJECT_ID)
                .savedAt(savedAt);
        assertThat(counts).usingRecursiveComparison().isEqualTo(List.of(
                row.topicId(large.id()).traces(70).rank(1).build(),
                row.topicId(small.id()).traces(10).rank(2).build(),
                row.topicId(TraceTopic.OTHER).traces(20).rank(null).build()));
    }

    private static Topic topic(String id, LocalDate born, int size) {
        return Topic.builder()
                .id(UUID.fromString(id))
                .workspaceId(WORKSPACE_ID)
                .projectId(PROJECT_ID)
                .name("Topic " + id)
                .definition("Requests of kind " + id)
                .born(born)
                .size(size)
                .build();
    }
}
