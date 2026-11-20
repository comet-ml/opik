package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.topics.TopicsBackfillGate.State;
import com.comet.opik.domain.topics.TopicsFirstBuildCandidates.Candidate;
import com.comet.opik.infrastructure.TopicsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The first build's gate and retry wait. With the test config, a workspace is fresh for 2 days (twice
 * {@code longWindowInterval}), the longest backfill wait is 72 hours, and the retry waits are 1h, 6h and 1d.
 */
@DisplayName("topics first-build gate and retry wait")
class TopicsBackfillGateTest {

    private static final Instant NOW = Instant.now();
    private static final TopicsConfig CONFIG = TestConfigUtils.loadConfigTest().getTopics();
    private static final TopicsBackfillGate GATE = new TopicsBackfillGate(CONFIG);
    private static final TopicsFirstBuildCandidates CANDIDATES = new TopicsFirstBuildCandidates(CONFIG,
            mock(TransactionTemplate.class), mock(TopicWindowDAO.class), GATE);

    private static ListlessProject.ListlessProjectBuilder project() {
        return ListlessProject.builder().workspaceId(UUID.randomUUID().toString()).projectId(UUID.randomUUID());
    }

    private static Instant ago(long hours) {
        return NOW.minus(Duration.ofHours(hours));
    }

    static Stream<Arguments> gates() {
        return Stream.of(
                Arguments.of("no backfill, fresh workspace", project().vectorsCheckedAt(ago(47)), State.OPEN),
                Arguments.of("no backfill, stale workspace", project().vectorsCheckedAt(ago(49)), State.WAITING),
                Arguments.of("no backfill, never checked", project(), State.WAITING),
                Arguments.of("drained, fresh workspace", project().vectorsCheckedAt(ago(1)).backfillStartedAt(ago(5))
                        .backfillDrainedAt(ago(2)), State.OPEN),
                Arguments.of("drained, stale workspace", project().vectorsCheckedAt(ago(49))
                        .backfillStartedAt(ago(60)).backfillDrainedAt(ago(50)), State.WAITING),
                Arguments.of("not drained within the longest wait", project().vectorsCheckedAt(ago(1))
                        .backfillStartedAt(ago(72)).backfillTracesTotal(100).backfillTracesDone(100), State.WAITING),
                Arguments.of("past the longest wait, enough vectors", project().vectorsCheckedAt(ago(1))
                        .backfillStartedAt(ago(73)).backfillTracesTotal(1000).backfillTracesDone(995),
                        State.TIMED_OUT_OPEN),
                Arguments.of("past the longest wait, too few vectors", project().vectorsCheckedAt(ago(1))
                        .backfillStartedAt(ago(73)).backfillTracesTotal(1000).backfillTracesDone(985),
                        State.TIMED_OUT_WAITING),
                Arguments.of("past the longest wait, nothing counted", project().vectorsCheckedAt(ago(1))
                        .backfillStartedAt(ago(73)), State.TIMED_OUT_WAITING));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gates")
    @DisplayName("a project's gate state from its backfill and its workspace's last check")
    void gate(String name, ListlessProject.ListlessProjectBuilder project, State expected) {
        assertThat(GATE.firstBuildGate(project.build(), NOW)).isEqualTo(expected);
    }

    static Stream<Arguments> retries() {
        return Stream.of(
                Arguments.of("never failed", project(), true),
                Arguments.of("one failure, under 1h ago", project().failedRuns(1).runFinishedAt(NOW.minusSeconds(3599)),
                        false),
                Arguments.of("one failure, 1h ago", project().failedRuns(1).runFinishedAt(ago(1)), true),
                Arguments.of("two failures, 5h ago", project().failedRuns(2).runFinishedAt(ago(5)), false),
                Arguments.of("two failures, 6h ago", project().failedRuns(2).runFinishedAt(ago(6)), true),
                Arguments.of("five failures, 23h ago", project().failedRuns(5).runFinishedAt(ago(23)), false),
                Arguments.of("five failures, 1d ago", project().failedRuns(5).runFinishedAt(ago(24)), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retries")
    @DisplayName("a failed first build waits 1h, then 6h, then 1d before the next")
    void retryDue(String name, ListlessProject.ListlessProjectBuilder project, boolean due) {
        assertThat(CANDIDATES.retryDue(project.build(), NOW)).isEqualTo(due);
    }

    @Test
    @DisplayName("the candidates take one project of each workspace in turn, in the workspaces' order")
    void roundRobin() {
        var a1 = candidate("a", 900);
        var a2 = candidate("a", 800);
        var a3 = candidate("a", 700);
        var b1 = candidate("b", 100);
        var c1 = candidate("c", 500);
        var c2 = candidate("c", 400);

        assertThat(TopicsFirstBuildCandidates.roundRobin(List.of(List.of(a1, a2, a3), List.of(b1), List.of(c1, c2))))
                .containsExactly(a1, b1, c1, a2, c2, a3);
    }

    private static Candidate candidate(String workspaceId, long vectors) {
        return Candidate.builder().workspaceId(workspaceId).projectId(UUID.randomUUID()).vectors(vectors).build();
    }
}
