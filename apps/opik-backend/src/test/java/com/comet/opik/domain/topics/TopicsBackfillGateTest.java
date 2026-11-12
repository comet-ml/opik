package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.TopicsConfig;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.domain.topics.TopicsBackfillGate.State.OPEN;
import static com.comet.opik.domain.topics.TopicsBackfillGate.State.TIMED_OUT_OPEN;
import static com.comet.opik.domain.topics.TopicsBackfillGate.State.TIMED_OUT_WAITING;
import static com.comet.opik.domain.topics.TopicsBackfillGate.State.WAITING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first build's gate, from the rows of {@code topic_vector_backfills}: with the test config, a row is fresh for 2
 * days (twice {@code longWindowInterval}) and the longest wait is 72 hours.
 */
@DisplayName("topics backfill gate")
class TopicsBackfillGateTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final UUID project = UUID.randomUUID();
    private final List<TopicVectorBackfill> rows = new ArrayList<>();
    private TopicVectorBackfillDAO dao;
    private TopicsBackfillGate gate;

    @BeforeEach
    void setUp() {
        dao = mock(TopicVectorBackfillDAO.class);
        when(dao.findByWorkspaceIds(any())).thenAnswer(invocation -> List.copyOf(rows));
        var handle = mock(Handle.class);
        when(handle.attach(TopicVectorBackfillDAO.class)).thenReturn(dao);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
        assertThat(config.getVectors().getLongWindowInterval().toJavaDuration()).isEqualTo(Duration.ofDays(1));
        assertThat(config.getFirstBuild().getBackfillMaxWait().toJavaDuration()).isEqualTo(Duration.ofHours(72));
        gate = new TopicsBackfillGate(config, template);
    }

    @Test
    @DisplayName("a drained row checked within twice the long-window interval opens")
    void drainedAndFreshOpens() {
        rows.add(row(project).drainedAt(NOW.minus(Duration.ofHours(30))).checkedAt(NOW.minus(Duration.ofHours(47)))
                .build());

        assertThat(state(project)).isEqualTo(OPEN);
    }

    @Test
    @DisplayName("a drained row not checked for twice the long-window interval waits: the tick stopped for the "
            + "workspace")
    void drainedButStaleWaits() {
        rows.add(row(project).drainedAt(NOW.minus(Duration.ofHours(60))).checkedAt(NOW.minus(Duration.ofHours(49)))
                .build());

        assertThat(state(project)).isEqualTo(WAITING);
    }

    @Test
    @DisplayName("a row not drained waits, whatever its progress, until the longest wait")
    void undrainedWaits() {
        rows.add(row(project).startedAt(NOW.minus(Duration.ofHours(72))).tracesTotal(1000).tracesDone(1000).build());

        assertThat(state(project)).isEqualTo(WAITING);
    }

    @Test
    @DisplayName("past the longest wait, a row with at least 99% of its traces embedded opens as timed out")
    void timedOutWithEnoughOpens() {
        rows.add(row(project).startedAt(NOW.minus(Duration.ofHours(73))).tracesTotal(1000).tracesDone(990).build());

        assertThat(state(project)).isEqualTo(TIMED_OUT_OPEN);
        assertThat(TIMED_OUT_OPEN.isOpen()).isTrue();
        assertThat(TIMED_OUT_OPEN.getValue()).isEqualTo("timed_out_open");
    }

    @Test
    @DisplayName("past the longest wait, a row with under 99% of its traces embedded keeps waiting, as timed out")
    void timedOutWithTooFewWaits() {
        rows.add(row(project).startedAt(NOW.minus(Duration.ofHours(73))).tracesTotal(1000).tracesDone(989).build());

        assertThat(state(project)).isEqualTo(TIMED_OUT_WAITING);
        assertThat(TIMED_OUT_WAITING.isOpen()).isFalse();
        assertThat(TIMED_OUT_WAITING.getValue()).isEqualTo("timed_out_waiting");
    }

    @Test
    @DisplayName("past the longest wait, a row with no trace counted keeps waiting")
    void timedOutWithNoTotalWaits() {
        rows.add(row(project).startedAt(NOW.minus(Duration.ofHours(100))).build());

        assertThat(state(project)).isEqualTo(TIMED_OUT_WAITING);
    }

    @Test
    @DisplayName("a project with no row opens when its workspace has a fresh row, drained or not")
    void noRowInAFreshWorkspaceOpens() {
        rows.add(row(UUID.randomUUID()).build());

        assertThat(state(project)).isEqualTo(OPEN);
    }

    @Test
    @DisplayName("a project with no row waits when its workspace has no row, only stale rows, or rows of another "
            + "workspace only")
    void noRowInAnUnseenWorkspaceWaits() {
        assertThat(state(project)).isEqualTo(WAITING);

        rows.add(row(UUID.randomUUID()).drainedAt(NOW.minus(Duration.ofDays(5)))
                .checkedAt(NOW.minus(Duration.ofDays(3))).build());
        rows.add(row(UUID.randomUUID()).workspaceId("ws-2").build());

        assertThat(state(project)).isEqualTo(WAITING);
    }

    @Test
    @DisplayName("one MySQL read serves every project asked; no workspace, no read")
    void oneRead() {
        var other = UUID.randomUUID();
        rows.add(row(project).drainedAt(NOW).build());
        rows.add(row(other).build());

        var read = gate.read(List.of(WORKSPACE_ID, "ws-2"), NOW);

        assertThat(read.state(WORKSPACE_ID, project)).isEqualTo(OPEN);
        assertThat(read.state(WORKSPACE_ID, other)).isEqualTo(WAITING);
        assertThat(read.state("ws-2", project)).isEqualTo(WAITING);
        assertThat(read.waited(WORKSPACE_ID, other)).isEqualTo(Duration.ofHours(1));
        assertThat(read.waited("ws-2", project)).isZero();
        verify(dao, times(1)).findByWorkspaceIds(any());

        assertThat(gate.read(List.of(), NOW).state(WORKSPACE_ID, project)).isEqualTo(WAITING);
        verify(dao, times(1)).findByWorkspaceIds(any());
        verify(dao, never()).findByKeys(any(), any());
    }

    private TopicsBackfillGate.State state(UUID projectId) {
        return gate.read(List.of(WORKSPACE_ID), NOW).state(WORKSPACE_ID, projectId);
    }

    /** A row started and checked an hour ago, not drained, nothing counted. */
    private static TopicVectorBackfill.TopicVectorBackfillBuilder row(UUID projectId) {
        return TopicVectorBackfill.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .startedAt(NOW.minus(Duration.ofHours(1)))
                .checkedAt(NOW.minus(Duration.ofHours(1)))
                .lastDoneAt(NOW.minus(Duration.ofHours(2)));
    }
}
