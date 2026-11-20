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
 * The first build's gate, from the projects' backfills and the workspaces' last check: with the test config, a
 * workspace is fresh for 2 days (twice {@code longWindowInterval}) and the longest wait is 72 hours.
 */
@DisplayName("topics backfill gate")
class TopicsBackfillGateTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final UUID project = UUID.randomUUID();
    private final List<TopicVectorBackfillDAO.Backfill> rows = new ArrayList<>();
    private final List<TopicVectorBackfillDAO.Workspace> workspaces = new ArrayList<>();
    private TopicVectorBackfillDAO dao;
    private TopicsBackfillGate gate;

    @BeforeEach
    void setUp() {
        dao = mock(TopicVectorBackfillDAO.class);
        when(dao.findByWorkspaceIds(any())).thenAnswer(invocation -> List.copyOf(rows));
        when(dao.findWorkspaces(any())).thenAnswer(invocation -> List.copyOf(workspaces));
        var handle = mock(Handle.class);
        when(handle.attach(TopicVectorBackfillDAO.class)).thenReturn(dao);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
        assertThat(config.getVectors().getLongWindowInterval().toJavaDuration()).isEqualTo(Duration.ofDays(1));
        assertThat(config.getFirstBuild().getBackfillMaxWait().toJavaDuration()).isEqualTo(Duration.ofHours(72));
        gate = new TopicsBackfillGate(config, template);
        checked(WORKSPACE_ID, NOW.minus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("a drained backfill opens when its workspace was checked within twice the long-window interval")
    void drainedAndFreshOpens() {
        rows.add(row(project).backfillDrainedAt(NOW.minus(Duration.ofHours(30))).build());
        checked(WORKSPACE_ID, NOW.minus(Duration.ofHours(47)));

        assertThat(state(project)).isEqualTo(OPEN);
    }

    @Test
    @DisplayName("a drained backfill waits when its workspace was not checked for twice the long-window interval: the "
            + "tick stopped for the workspace")
    void drainedButStaleWaits() {
        rows.add(row(project).backfillDrainedAt(NOW.minus(Duration.ofHours(60))).build());
        checked(WORKSPACE_ID, NOW.minus(Duration.ofHours(49)));

        assertThat(state(project)).isEqualTo(WAITING);
    }

    @Test
    @DisplayName("a backfill not drained waits, whatever its progress, until the longest wait")
    void undrainedWaits() {
        rows.add(row(project).backfillStartedAt(NOW.minus(Duration.ofHours(72))).backfillTracesTotal(1000)
                .backfillTracesDone(1000).build());

        assertThat(state(project)).isEqualTo(WAITING);
    }

    @Test
    @DisplayName("past the longest wait, a backfill with at least 99% of its traces embedded opens as timed out")
    void timedOutWithEnoughOpens() {
        rows.add(row(project).backfillStartedAt(NOW.minus(Duration.ofHours(73))).backfillTracesTotal(1000)
                .backfillTracesDone(990).build());

        assertThat(state(project)).isEqualTo(TIMED_OUT_OPEN);
        assertThat(TIMED_OUT_OPEN.isOpen()).isTrue();
        assertThat(TIMED_OUT_OPEN.getValue()).isEqualTo("timed_out_open");
    }

    @Test
    @DisplayName("past the longest wait, a backfill with under 99% of its traces embedded keeps waiting, as timed out")
    void timedOutWithTooFewWaits() {
        rows.add(row(project).backfillStartedAt(NOW.minus(Duration.ofHours(73))).backfillTracesTotal(1000)
                .backfillTracesDone(989).build());

        assertThat(state(project)).isEqualTo(TIMED_OUT_WAITING);
        assertThat(TIMED_OUT_WAITING.isOpen()).isFalse();
        assertThat(TIMED_OUT_WAITING.getValue()).isEqualTo("timed_out_waiting");
    }

    @Test
    @DisplayName("past the longest wait, a backfill with no trace counted keeps waiting")
    void timedOutWithNoTotalWaits() {
        rows.add(row(project).backfillStartedAt(NOW.minus(Duration.ofHours(100))).build());

        assertThat(state(project)).isEqualTo(TIMED_OUT_WAITING);
    }

    @Test
    @DisplayName("a project with no backfill opens when its workspace is fresh")
    void noBackfillInAFreshWorkspaceOpens() {

        assertThat(state(project)).isEqualTo(OPEN);
    }

    @Test
    @DisplayName("a project with no backfill waits when its workspace was never checked, or not lately, whatever "
            + "another workspace's check")
    void noBackfillInAnUnseenWorkspaceWaits() {
        workspaces.clear();
        checked("ws-2", NOW);

        assertThat(state(project)).isEqualTo(WAITING);

        checked(WORKSPACE_ID, NOW.minus(Duration.ofDays(3)));

        assertThat(state(project)).isEqualTo(WAITING);
    }

    @Test
    @DisplayName("one read of each table serves every project asked; no workspace, no read")
    void oneRead() {
        var other = UUID.randomUUID();
        rows.add(row(project).backfillDrainedAt(NOW).build());
        rows.add(row(other).build());

        var read = gate.read(List.of(WORKSPACE_ID, "ws-2"), NOW);

        assertThat(read.state(WORKSPACE_ID, project)).isEqualTo(OPEN);
        assertThat(read.state(WORKSPACE_ID, other)).isEqualTo(WAITING);
        assertThat(read.state("ws-2", project)).isEqualTo(WAITING);
        assertThat(read.waited(WORKSPACE_ID, other)).isEqualTo(Duration.ofHours(1));
        assertThat(read.waited("ws-2", project)).isZero();
        verify(dao, times(1)).findByWorkspaceIds(any());
        verify(dao, times(1)).findWorkspaces(any());

        assertThat(gate.read(List.of(), NOW).state(WORKSPACE_ID, project)).isEqualTo(WAITING);
        verify(dao, times(1)).findByWorkspaceIds(any());
        verify(dao, times(1)).findWorkspaces(any());
        verify(dao, never()).findByKeys(any(), any());
    }

    private TopicsBackfillGate.State state(UUID projectId) {
        return gate.read(List.of(WORKSPACE_ID), NOW).state(WORKSPACE_ID, projectId);
    }

    private void checked(String workspaceId, Instant at) {
        workspaces.removeIf(workspace -> workspace.workspaceId().equals(workspaceId));
        workspaces
                .add(TopicVectorBackfillDAO.Workspace.builder().workspaceId(workspaceId).vectorsCheckedAt(at).build());
    }

    /** A backfill started an hour ago, not drained, nothing counted. */
    private static TopicVectorBackfillDAO.Backfill.BackfillBuilder row(UUID projectId) {
        return TopicVectorBackfillDAO.Backfill.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .backfillStartedAt(NOW.minus(Duration.ofHours(1)))
                .backfillLastDoneAt(NOW.minus(Duration.ofHours(2)));
    }
}
