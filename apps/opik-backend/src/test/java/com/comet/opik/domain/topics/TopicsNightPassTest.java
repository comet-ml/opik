package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.error.ConflictException;
import com.comet.opik.infrastructure.TopicsConfig;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.domain.topics.TopicsMetricsReader.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The planner's night pass with every store mocked: the night the schedule gives, each project's decision with its
 * reason (counted and written to {@code topic_projects}), the queue up to the free slots, the budget's cut-off and the
 * night's end, a failed read, and a night by hand.
 */
@DisplayName("topics night pass")
class TopicsNightPassTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 2);
    private static final Instant START = Instant.parse("2026-11-02T02:00:00Z");
    private static final Instant NIGHT_START = NIGHT.atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant IN_BUDGET = START.plusSeconds(600);
    private static final Instant AFTER_BUDGET = START.plusSeconds(3 * 3600);

    /** Each project's row; its window count in {@link #vectors}, or a failed count when absent. */
    private final Map<UUID, TopicProject> rows = new HashMap<>();
    private final Map<UUID, Long> vectors = new HashMap<>();
    private final Set<UUID> deleted = new HashSet<>();
    private final Set<UUID> paused = new HashSet<>();
    private final List<TopicsRunPlanner.Planned> queued = new ArrayList<>();
    private Function<TopicsRunPlanner.Planned, Mono<UUID>> queue;

    private InMemoryMetricReader reader;
    private TopicsConfig config;
    private TopicDAO topicDAO;
    private TopicProjectDAO projectDAO;
    private TopicProjectNightDAO nightDAO;
    private TopicWorkspaceService workspaceService;
    private TopicsFirstBuildCandidates candidates;
    private TopicsNightPass pass;

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        config = TestConfigUtils.loadConfigTest().getTopics();
        topicDAO = mock(TopicDAO.class);
        projectDAO = mock(TopicProjectDAO.class);
        nightDAO = mock(TopicProjectNightDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        when(handle.attach(TopicProjectDAO.class)).thenReturn(projectDAO);
        when(handle.attach(TopicProjectNightDAO.class)).thenReturn(nightDAO);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));

        when(topicDAO.findProjectsWithTopics(anyCollection())).thenAnswer(invocation -> rows.keySet().stream()
                .map(projectId -> new TopicProjectKey(WORKSPACE_ID, projectId)).toList());
        when(projectDAO.findProjects(eq(WORKSPACE_ID), any())).thenAnswer(invocation -> invocation
                .<Collection<UUID>>getArgument(1).stream().map(rows::get).toList());
        when(topicDAO.findExistingProjectIds(eq(WORKSPACE_ID), any())).thenAnswer(invocation -> {
            var ids = new HashSet<>(invocation.<Collection<UUID>>getArgument(1));
            ids.removeAll(deleted);
            return ids;
        });
        when(projectDAO.findPausedProjectIds(eq(WORKSPACE_ID), any(), anyInt(), any())).thenAnswer(invocation -> {
            var ids = new HashSet<>(invocation.<Collection<UUID>>getArgument(1));
            ids.retainAll(paused);
            return ids;
        });

        candidates = mock(TopicsFirstBuildCandidates.class);
        when(candidates.windowVectors(anyString(), any(), any())).thenAnswer(invocation -> {
            var count = vectors.get(invocation.<UUID>getArgument(1));
            return count == null ? Mono.error(new IllegalStateException("ClickHouse guard tripped")) : Mono.just(count);
        });
        workspaceService = mock(TopicWorkspaceService.class);
        when(workspaceService.isEnabled(WORKSPACE_ID)).thenReturn(true);
        queue = planned -> {
            queued.add(planned);
            return Mono.just(UUID.randomUUID());
        };
        pass = new TopicsNightPass(config, template, workspaceService, candidates,
                TopicsMetricsReader.metrics(reader), workspaceId -> "name of " + workspaceId);
    }

    /** A project with a list saved for the night before, and its window count. */
    private UUID listed(Long count) {
        var projectId = UUID.randomUUID();
        rows.put(projectId, row(projectId).build());
        if (count != null) {
            vectors.put(projectId, count);
        }
        return projectId;
    }

    private static TopicProject.TopicProjectBuilder row(UUID projectId) {
        return TopicProject.builder().workspaceId(WORKSPACE_ID).projectId(projectId).listRunId(UUID.randomUUID())
                .windowUntil(NIGHT_START.minusSeconds(86_400));
    }

    private int plan(Instant now, int free) {
        return pass.plan(List.of(WORKSPACE_ID), now, free, queue).block();
    }

    private double decisions(String decision, String reason) {
        return reason == null
                ? sum(reader, TopicsMetrics.NIGHTLY_DECISIONS, "decision", decision)
                : sum(reader, TopicsMetrics.NIGHTLY_DECISIONS, "decision", decision, "reason", reason);
    }

    @Test
    @DisplayName("the night is the UTC date of the schedule's latest fire, today or yesterday, and starts at its first "
            + "fire that day; a schedule that fired on neither day gives none")
    void currentNight() {
        assertThat(TopicsNightPass.current("0 0 2 * * ?", Instant.parse("2026-11-02T03:00:00Z")))
                .contains(new TopicsNightPass.Night(NIGHT, START));
        assertThat(TopicsNightPass.current("0 0 2 * * ?", Instant.parse("2026-11-02T01:00:00Z")))
                .contains(new TopicsNightPass.Night(NIGHT.minusDays(1), START.minusSeconds(86_400)));
        assertThat(TopicsNightPass.current("0 0 * * * ?", Instant.parse("2026-11-02T05:30:00Z")))
                .contains(new TopicsNightPass.Night(NIGHT, NIGHT_START));
        // 2026-11-02 is a Monday: a Friday-only schedule fired on neither day.
        assertThat(TopicsNightPass.current("0 0 2 ? * FRI", Instant.parse("2026-11-02T05:30:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("within the budget each project is decided once, by reason: deleted, no list, already saved, paused "
            + "and small are skipped, a failed count is count_failed, the rest is queued with its count and night; "
            + "a project looked at tonight or with a run in flight is left alone")
    void decidesEachProject() {
        var gone = listed(500L);
        deleted.add(gone);
        var noList = listed(500L);
        rows.put(noList, row(noList).listRunId(null).build());
        var saved = listed(500L);
        rows.put(saved, row(saved).windowUntil(NIGHT_START).build());
        var stopped = listed(500L);
        paused.add(stopped);
        var small = listed(50L);
        var broken = listed(null);
        var built = listed(500L);
        var lookedAt = listed(500L);
        rows.put(lookedAt, row(lookedAt).lastNight(NIGHT).build());
        var running = listed(500L);
        rows.put(running, row(running).runStatus(TopicProject.RunStatus.RUNNING).build());

        assertThat(plan(IN_BUDGET, 5)).isEqualTo(1);

        assertThat(queued).containsExactly(new TopicsRunPlanner.Planned(WORKSPACE_ID, built,
                TopicProject.RunMode.NIGHTLY_UPDATE, NIGHT, 500));
        for (var reason : List.of("deleted", "no_list", "already_saved", "paused", "small")) {
            assertThat(decisions("skip", reason)).as(reason).isEqualTo(1);
        }
        assertThat(decisions("count_failed", null)).isEqualTo(1);
        assertThat(decisions("nightly_update", null)).isEqualTo(1);
        assertThat(decisions("not_reached", null)).isZero();
        for (var projectId : List.of(gone, noList, saved, stopped, small, broken, built)) {
            verify(nightDAO).markLooked(WORKSPACE_ID, Set.of(projectId), NIGHT);
        }
        verify(nightDAO, never()).markLooked(eq(WORKSPACE_ID), eq(Set.of(lookedAt)), any());
        verify(nightDAO, never()).markLooked(eq(WORKSPACE_ID), eq(Set.of(running)), any());
        verify(nightDAO, never()).markNotReached(anyString(), any(), any());
    }

    @Test
    @DisplayName("with no free slot the skips that need no count are still written, and nothing is counted or "
            + "queued; with slots it queues up to them, stalest window first")
    void queuesUpToTheFreeSlots() {
        var gone = listed(500L);
        deleted.add(gone);
        var older = listed(500L);
        rows.put(older, row(older).windowUntil(NIGHT_START.minusSeconds(3 * 86_400)).build());
        var newer = listed(500L);
        var never = listed(500L);
        rows.put(never, row(never).windowUntil(null).build());

        assertThat(plan(IN_BUDGET, 0)).isZero();

        assertThat(decisions("skip", "deleted")).isEqualTo(1);
        verify(candidates, never()).windowVectors(anyString(), any(), any());

        rows.put(gone, row(gone).lastNight(NIGHT).build());
        assertThat(plan(IN_BUDGET, 2)).isEqualTo(2);

        assertThat(queued).extracting(TopicsRunPlanner.Planned::projectId).containsExactly(never, older);
        verify(nightDAO, never()).markLooked(WORKSPACE_ID, Set.of(newer), NIGHT);
    }

    @Test
    @DisplayName("a project that got a run between the read and its queue is not decided: a later plan decides it")
    void projectInFlightMeanwhile() {
        var projectId = listed(500L);
        queue = planned -> Mono.empty();

        assertThat(plan(IN_BUDGET, 2)).isZero();

        verify(nightDAO, never()).markLooked(anyString(), any(), any());
        assertThat(decisions("nightly_update", null)).isZero();
        assertThat(rows).containsKey(projectId);
    }

    @Test
    @DisplayName("after the budget no project is read or queued: the ones the night did not look at are written not "
            + "reached in one write per workspace, and the night is counted finished once")
    void endsAfterTheBudget() {
        var left = List.of(new TopicProjectKey(WORKSPACE_ID, UUID.randomUUID()),
                new TopicProjectKey(WORKSPACE_ID, UUID.randomUUID()));
        when(nightDAO.findNotReached(List.of(WORKSPACE_ID), NIGHT)).thenReturn(left, List.of());
        listed(500L);

        assertThat(plan(AFTER_BUDGET, 2)).isZero();
        assertThat(plan(AFTER_BUDGET, 2)).isZero();

        verify(nightDAO).markNotReached(WORKSPACE_ID,
                Set.of(left.get(0).projectId(), left.get(1).projectId()), NIGHT);
        verify(topicDAO, never()).findProjectsWithTopics(anyCollection());
        assertThat(queued).isEmpty();
        assertThat(decisions("not_reached", null)).isEqualTo(2);
        assertThat(sum(reader, TopicsMetrics.NIGHTLY_NIGHTS, "outcome", "finished")).isEqualTo(1);
        assertThat(sum(reader, TopicsMetrics.NIGHTLY_BUDGET_USED)).isEqualTo(
                config.getNightly().getBudget().toSeconds());
    }

    @Test
    @DisplayName("a pass whose read fails is counted as a failed night and queues nothing; with the job off nothing "
            + "is read")
    void failedReadAndJobOff() {
        when(topicDAO.findProjectsWithTopics(anyCollection())).thenThrow(new IllegalStateException("MySQL down"));

        assertThat(plan(IN_BUDGET, 2)).isZero();
        assertThat(sum(reader, TopicsMetrics.NIGHTLY_NIGHTS, "outcome", "failed")).isEqualTo(1);

        config.getNightly().setEnabled(false);
        assertThat(plan(IN_BUDGET, 2)).isZero();
        verify(topicDAO).findProjectsWithTopics(anyCollection());
    }

    @Test
    @DisplayName("a night by hand queues the project's nightly update with its count; it is refused with a 409 when "
            + "the job is off, the project has no list, its list covers the night, its window is small, or it has a "
            + "run in flight")
    void nightByHand() {
        var built = listed(500L);
        var noList = listed(500L);
        rows.put(noList, row(noList).listRunId(null).build());
        var saved = listed(500L);
        rows.put(saved, row(saved).windowUntil(NIGHT_START).build());
        var small = listed(50L);

        assertThat(pass.byHand(WORKSPACE_ID, built, NIGHT, queue).block()).isNotNull();
        assertThat(queued).containsExactly(new TopicsRunPlanner.Planned(WORKSPACE_ID, built,
                TopicProject.RunMode.NIGHTLY_UPDATE, NIGHT, 500));

        for (var refused : List.of(noList, saved, small)) {
            assertThatThrownBy(() -> pass.byHand(WORKSPACE_ID, refused, NIGHT, queue).block())
                    .isInstanceOf(ConflictException.class);
        }
        assertThatThrownBy(() -> pass.byHand(WORKSPACE_ID, built, NIGHT, planned -> Mono.empty()).block())
                .isInstanceOf(ConflictException.class);
        config.getNightly().setEnabled(false);
        assertThatThrownBy(() -> pass.byHand(WORKSPACE_ID, built, NIGHT, queue).block())
                .isInstanceOf(ConflictException.class);
        assertThat(queued).hasSize(1);
        verify(nightDAO, never()).markLooked(anyString(), any(), any());
    }

    @Test
    @DisplayName("an empty topics.nightly.workspaces covers every workspace topics run for")
    void emptyNightlyWorkspaces() {
        config.getNightly().setWorkspaces("");
        var projectId = listed(500L);

        assertThat(plan(IN_BUDGET, 2)).isEqualTo(1);
        assertThat(pass.byHand(WORKSPACE_ID, projectId, NIGHT, queue).block()).isNotNull();
    }

    @Test
    @DisplayName("a set topics.nightly.workspaces limits the night and the night by hand to the listed workspaces")
    void nightlyWorkspacesSubset() {
        config.getNightly().setWorkspaces("other-workspace");
        var projectId = listed(500L);

        assertThat(plan(IN_BUDGET, 2)).isZero();
        assertThatThrownBy(() -> pass.byHand(WORKSPACE_ID, projectId, NIGHT, queue).block())
                .isInstanceOf(ConflictException.class);
        assertThat(queued).isEmpty();

        config.getNightly().setWorkspaces(" other-workspace , " + WORKSPACE_ID);
        assertThat(plan(IN_BUDGET, 2)).isEqualTo(1);
    }

    @Test
    @DisplayName("a listed workspace that topics do not run for gets no nightly update")
    void nightlyWorkspaceOutsideEnabledWorkspaces() {
        config.getNightly().setWorkspaces(WORKSPACE_ID + ",not-enabled");
        var projectId = listed(500L);

        assertThat(plan(IN_BUDGET, 2)).isEqualTo(1);
        assertThat(queued).extracting(TopicsRunPlanner.Planned::workspaceId).containsExactly(WORKSPACE_ID);
        assertThatThrownBy(() -> pass.byHand("not-enabled", projectId, NIGHT, queue).block())
                .isInstanceOf(ConflictException.class);
        assertThat(queued).hasSize(1);
    }
}
