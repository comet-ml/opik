package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("night triage")
class NightTriageTest {

    private static final String WORKSPACE_ID = "workspace";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final TopicsNight NIGHT = new TopicsNight(DATE, Instant.parse("2026-11-02T02:00:00Z"));
    private static final Instant DAY_BEFORE = NIGHT.windowTo().minus(Duration.ofDays(1));
    private static final int PAUSE_FAILURES = 3;
    private static final int PAUSE_NIGHTS = 7;

    @Test
    @DisplayName("a deleted project, a list that covers the night and a project paused by its failures are skipped "
            + "with their reason; a paused project whose last failure is older than pauseNights, or with fewer "
            + "failures, is counted")
    void skips() {
        var gone = row(1).build();
        var saved = row(2).windowUntil(NIGHT.windowTo()).build();
        var paused = row(3).failedRuns(PAUSE_FAILURES).runStartedAt(DAY_BEFORE).build();
        var pausedLongAgo = row(4).failedRuns(PAUSE_FAILURES)
                .runStartedAt(NIGHT.windowTo().minus(Duration.ofDays(PAUSE_NIGHTS + 1))).build();
        var fewFailures = row(5).failedRuns(PAUSE_FAILURES - 1).runStartedAt(DAY_BEFORE).build();
        var listed = row(6).build();
        var rows = List.of(gone, saved, paused, pausedLongAgo, fewFailures, listed);

        var due = NightTriage.triage(rows, keys(saved, paused, pausedLongAgo, fewFailures, listed), NIGHT,
                PAUSE_FAILURES, PAUSE_NIGHTS);

        assertThat(due.skipped()).isEqualTo(Map.of(
                NightSkipReason.DELETED, List.of(key(gone)),
                NightSkipReason.ALREADY_SAVED, List.of(key(saved)),
                NightSkipReason.PAUSED, List.of(key(paused))));
        assertThat(due.candidates()).extracting(NightTriage.NightCandidate::projectId)
                .containsExactlyInAnyOrder(pausedLongAgo.projectId(), fewFailures.projectId(), listed.projectId());
    }

    @Test
    @DisplayName("the candidates go stalest first: no window first, then the oldest window, then by project id")
    void queueOrder() {
        var newer = row(1).windowUntil(DAY_BEFORE).build();
        var olderHigh = row(9).windowUntil(DAY_BEFORE.minus(Duration.ofDays(2))).build();
        var olderLow = row(2).windowUntil(DAY_BEFORE.minus(Duration.ofDays(2))).build();
        var never = row(5).windowUntil(null).build();
        var rows = List.of(newer, olderHigh, olderLow, never);

        var due = NightTriage.triage(rows, keys(newer, olderHigh, olderLow, never), NIGHT, PAUSE_FAILURES,
                PAUSE_NIGHTS);

        assertThat(due.candidates()).extracting(NightTriage.NightCandidate::projectId).containsExactly(
                never.projectId(), olderLow.projectId(), olderHigh.projectId(), newer.projectId());
        assertThat(due.skipped()).isEmpty();
    }

    private static TopicProject.TopicProjectBuilder row(long id) {
        return TopicProject.builder().workspaceId(WORKSPACE_ID).projectId(new UUID(0, id)).listRunId(new UUID(1, id))
                .windowUntil(DAY_BEFORE);
    }

    private static TopicProjectKey key(TopicProject row) {
        return new TopicProjectKey(row.workspaceId(), row.projectId());
    }

    private static Set<TopicProjectKey> keys(TopicProject... rows) {
        return Stream.of(rows).map(NightTriageTest::key).collect(Collectors.toSet());
    }
}
