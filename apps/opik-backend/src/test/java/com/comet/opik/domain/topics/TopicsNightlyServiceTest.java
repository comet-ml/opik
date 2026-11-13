package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Duration;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One night with every store mocked: each project's decision with its reason, a failed count read, a run that cannot
 * start, the runs housekeeping finds abandoned, a paused project, a project with no live topic left, the night's
 * record, and the queue order.
 */
@DisplayName("topics nightly service")
class TopicsNightlyServiceTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final String BROKEN_WORKSPACE_ID = "ws-broken";
    private static final Instant START = Instant.parse("2026-11-02T02:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 2);

    private final UUID small = UUID.randomUUID();
    private final UUID deleted = UUID.randomUUID();
    private final UUID built = UUID.randomUUID();
    private final UUID locked = UUID.randomUUID();
    private final UUID started = UUID.randomUUID();
    private final UUID broken = UUID.randomUUID();

    /** Each listed project's window vectors; a project in {@link #failedCounts} fails its count read instead. */
    private final Map<UUID, Long> vectors = new HashMap<>();
    private final Set<UUID> failedCounts = new HashSet<>();
    private final List<TopicDAO.ProjectKey> listed = new ArrayList<>();

    private TopicsTestMeter meter;
    private TopicWorkspaceService workspaceService;
    private TopicRunDAO runDAO;
    private TopicDAO topicDAO;
    private TopicNightDAO nightDAO;
    private TopicsRebuildService rebuildService;
    private LockService lockService;
    private TopicsNightlyService service;

    @BeforeEach
    void setUp() {
        meter = new TopicsTestMeter();
        workspaceService = mock(TopicWorkspaceService.class);
        runDAO = mock(TopicRunDAO.class);
        topicDAO = mock(TopicDAO.class);
        nightDAO = mock(TopicNightDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicRunDAO.class)).thenReturn(runDAO);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        when(handle.attach(TopicNightDAO.class)).thenReturn(nightDAO);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));

        when(workspaceService.eligibleWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID, BROKEN_WORKSPACE_ID));
        when(runDAO.findAbandonedWorkspaceIds(any())).thenReturn(List.of());
        list(WORKSPACE_ID, small, 50);
        list(WORKSPACE_ID, deleted, 120);
        list(WORKSPACE_ID, built, 120);
        list(WORKSPACE_ID, locked, 130);
        list(WORKSPACE_ID, started, 140);
        list(BROKEN_WORKSPACE_ID, broken, 150);
        failedCounts.add(broken);
        when(topicDAO.findProjectsWithLiveTopics(any())).thenAnswer(invocation -> listed.stream()
                .filter(key -> invocation.<Collection<String>>getArgument(0).contains(key.workspaceId())).toList());
        when(topicDAO.findProjectsWithLiveTopics(anyString(), any()))
                .thenAnswer(invocation -> List.copyOf(invocation.<Collection<UUID>>getArgument(1)));
        // built's last saved run already covers the night: the night skips it under its lock.
        when(topicDAO.findStates(eq(WORKSPACE_ID), any()))
                .thenAnswer(invocation -> invocation.<Collection<UUID>>getArgument(1).contains(built)
                        ? List.of(TopicProjectState.builder().projectId(built).workspaceId(WORKSPACE_ID)
                                .windowUntil(NIGHT.atStartOfDay(ZoneOffset.UTC).toInstant()).build())
                        : List.of());
        when(topicDAO.findExistingProjectIds(eq(WORKSPACE_ID), any())).thenAnswer(invocation -> {
            var ids = new HashSet<>(invocation.<Collection<UUID>>getArgument(1));
            ids.remove(deleted);
            return ids;
        });
        var windowDAO = mock(TopicWindowDAO.class);
        when(windowDAO.countByProject(anyString(), any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            var ids = invocation.<Collection<UUID>>getArgument(1);
            if (ids.stream().anyMatch(failedCounts::contains)) {
                return Flux.error(new IllegalStateException("ClickHouse guard tripped"));
            }
            return Flux.fromIterable(ids).filter(vectors::containsKey)
                    .map(projectId -> new TopicWindowDAO.ProjectCount(projectId, vectors.get(projectId)));
        });

        lockService = mock(LockService.class);
        when(lockService.lockUsingToken(any(), any())).thenAnswer(invocation -> Mono.just(
                !invocation.<LockService.Lock>getArgument(0).equals(TopicsProjectRunner.projectLock(locked))));
        when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());

        rebuildService = mock(TopicsRebuildService.class);
        when(rebuildService.nightlyUpdate(any(), anyString(), any(), any()))
                .thenAnswer(invocation -> TopicsRebuildService.Outcome.builder().runId(invocation.getArgument(0))
                        .status(TopicRun.Status.SAVED).build());

        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());

        TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
        var metrics = new TopicsMetrics(meter.meter);
        var runner = new TopicsProjectRunner(config, template, rebuildService, metrics, lockService);
        service = new TopicsNightlyService(config, template, workspaceService, windowDAO, rebuildService, runner,
                metrics, lockService, idGenerator);
    }

    private void list(String workspaceId, UUID projectId, long traces) {
        listed.add(new TopicDAO.ProjectKey(workspaceId, projectId));
        vectors.put(projectId, traces);
    }

    @Test
    @DisplayName("a night started by hand answers 503 with Retry-After when Redis fails")
    void nightLockUnavailable() {
        when(lockService.bestEffortLock(eq(TopicsNightlyService.NIGHT_LOCK), any(), any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("Redis down")));

        assertThatThrownBy(() -> service.runNightNow(NIGHT))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, exception -> assertThat(
                        exception.getResponse().getHeaderString(HttpHeaders.RETRY_AFTER))
                        .isEqualTo(String.valueOf(TopicsProjectRunner.LOCK_RETRY_AFTER_SECONDS)));
    }

    @Test
    @DisplayName("each project's decision is counted with its skip reason and workspace; a failed count read too")
    void decisions() {
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any())).thenReturn(UUID.randomUUID());

        var result = service.runNight(START);

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(4);
        for (var reason : List.of("small", "deleted", "already_saved", "locked")) {
            assertThat(meter.sum("topics.nightly.projects", "decision", "skip", "reason", reason, "workspace_id",
                    WORKSPACE_ID)).as(reason).isEqualTo(1);
        }
        assertThat(meter.sum("topics.nightly.projects", "decision", "nightly_update", "workspace_id", WORKSPACE_ID))
                .isEqualTo(1);
        assertThat(meter.points("topics.nightly.count_failures")).singleElement().satisfies(point -> {
            assertThat(point.get("workspace_id")).isEqualTo(BROKEN_WORKSPACE_ID);
            assertThat(point.get("error_type")).isNotBlank();
        });
        assertThat(meter.points("topics.runs")).isEmpty();
    }

    @Test
    @DisplayName("a count read that fails skips only its own project: another project of the workspace still runs")
    void countFailurePerProject() {
        var other = UUID.randomUUID();
        list(BROKEN_WORKSPACE_ID, other, 160);
        when(topicDAO.findExistingProjectIds(eq(BROKEN_WORKSPACE_ID), any()))
                .thenAnswer(invocation -> Set.copyOf(invocation.<Collection<UUID>>getArgument(1)));
        when(rebuildService.startRun(any(), any(), any())).thenReturn(UUID.randomUUID());

        service.runNight(START);

        verify(rebuildService).nightlyUpdate(any(), eq(BROKEN_WORKSPACE_ID), eq(other), eq(NIGHT));
        verify(rebuildService, never()).startRun(eq(BROKEN_WORKSPACE_ID), eq(broken), any());
        assertThat(rows()).filteredOn(row -> BROKEN_WORKSPACE_ID.equals(row.workspaceId()))
                .extracting(TopicNightProject::projectId, TopicNightProject::decision)
                .containsExactlyInAnyOrder(tuple(broken, "count_failed"), tuple(other, "nightly_update"));
    }

    @Test
    @DisplayName("a project with live topics gets a nightly update: its run and night row say so")
    void nightlyUpdate() {
        var runId = UUID.randomUUID();
        when(rebuildService.startRun(WORKSPACE_ID, started, TopicRun.Mode.NIGHTLY_UPDATE)).thenReturn(runId);

        var result = service.runNight(START);

        assertThat(result.saved()).isEqualTo(1);
        verify(rebuildService).nightlyUpdate(runId, WORKSPACE_ID, started, NIGHT);
        verify(rebuildService, never()).firstBuild(any(), any(), any(), any());
        assertThat(rows()).filteredOn(row -> started.equals(row.projectId())).singleElement().satisfies(row -> {
            assertThat(row.decision()).isEqualTo("nightly_update");
            assertThat(row.reason()).isNull();
            assertThat(row.runId()).isEqualTo(runId);
            assertThat(row.workspaceId()).isEqualTo(WORKSPACE_ID);
        });
    }

    @Test
    @DisplayName("a project whose live topics are gone by the time its lock is taken is skipped as no_list")
    void noList() {
        when(topicDAO.findProjectsWithLiveTopics(WORKSPACE_ID, Set.of(started))).thenReturn(List.of());

        var result = service.runNight(START);

        assertThat(result.saved()).isZero();
        verify(rebuildService, never()).startRun(any(), any(), any());
        assertThat(meter.sum("topics.nightly.projects", "decision", "skip", "reason", "no_list", "workspace_id",
                WORKSPACE_ID)).isEqualTo(1);
        assertThat(rows()).filteredOn(row -> started.equals(row.projectId()))
                .extracting(TopicNightProject::decision, TopicNightProject::reason)
                .containsExactly(tuple("skip", "no_list"));
    }

    @Test
    @DisplayName("a run that cannot start is counted once, as start_failed, and its decision and row are still written")
    void startFailure() {
        when(rebuildService.startRun(WORKSPACE_ID, started, TopicRun.Mode.NIGHTLY_UPDATE))
                .thenThrow(new IllegalStateException("MySQL down"));

        var result = service.runNight(START);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("start_failed");
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
        });
        assertThat(meter.sum("topics.nightly.projects", "decision", "nightly_update")).isEqualTo(1);
        assertThat(rows()).filteredOn(row -> started.equals(row.projectId())).singleElement().satisfies(row -> {
            assertThat(row.decision()).isEqualTo("nightly_update");
            assertThat(row.runId()).isNull();
        });
    }

    @Test
    @DisplayName("housekeeping counts each run it fails as abandoned, with the run's workspace")
    void abandonedRuns() {
        when(rebuildService.startRun(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(runDAO.findAbandonedWorkspaceIds(any())).thenReturn(List.of(WORKSPACE_ID, WORKSPACE_ID, "ws-2"));
        when(runDAO.failAbandoned(any(), any())).thenReturn(3);

        service.runNight(START);

        assertThat(meter.sum("topics.runs", "error_code", "abandoned", "workspace_id", WORKSPACE_ID)).isEqualTo(2);
        assertThat(meter.sum("topics.runs", "error_code", "abandoned", "workspace_id", "ws-2")).isEqualTo(1);
    }

    @Test
    @DisplayName("projects the night does not reach are counted per workspace, and get a not_reached row")
    void notReached() {
        Thread.currentThread().interrupt();
        try {
            var result = service.runNight(START);

            assertThat(result.notReached()).isEqualTo(3);
        } finally {
            Thread.interrupted();
        }
        assertThat(meter.sum("topics.nightly.projects", "decision", "not_reached", "workspace_id", WORKSPACE_ID))
                .isEqualTo(3);
        assertThat(meter.points("topics.runs")).isEmpty();
        assertThat(rows()).filteredOn(row -> "not_reached".equals(row.decision()))
                .extracting(TopicNightProject::projectId).containsExactlyInAnyOrder(built, locked, started);
    }

    @Test
    @DisplayName("the night's record: a row per project with its decision, reason and run, and its end")
    void nightRecord() {
        var runId = UUID.randomUUID();
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any())).thenReturn(runId);

        service.runNight(START);

        verify(nightDAO).start(eq(NIGHT), any());
        verify(nightDAO).deleteProjects(NIGHT);
        assertThat(rows()).extracting(TopicNightProject::workspaceId, TopicNightProject::projectId,
                TopicNightProject::decision, TopicNightProject::reason, TopicNightProject::runId)
                .containsExactlyInAnyOrder(
                        tuple(BROKEN_WORKSPACE_ID, broken, "count_failed", null, null),
                        tuple(WORKSPACE_ID, small, "skip", "small", null),
                        tuple(WORKSPACE_ID, deleted, "skip", "deleted", null),
                        tuple(WORKSPACE_ID, built, "skip", "already_saved", null),
                        tuple(WORKSPACE_ID, locked, "skip", "locked", null),
                        tuple(WORKSPACE_ID, started, "nightly_update", null, runId));
        assertThat(rows()).allSatisfy(row -> assertThat(row.night()).isEqualTo(NIGHT));
        assertThat(end()).satisfies(night -> {
            assertThat(night.night()).isEqualTo(NIGHT);
            assertThat(night.outcome()).isEqualTo(TopicNight.Outcome.FINISHED);
            assertThat(night.finishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a night with no workspace still writes its record, finished with no project")
    void emptyNight() {
        when(workspaceService.eligibleWorkspaceIds()).thenReturn(List.of());

        service.runNight(START);

        verify(nightDAO).start(eq(NIGHT), any());
        verify(nightDAO, never()).insertProjects(any());
        assertThat(end().outcome()).isEqualTo(TopicNight.Outcome.FINISHED);
    }

    @Test
    @DisplayName("a night whose read of the workspaces fails ends its record failed, and still throws")
    void failedNight() {
        when(workspaceService.eligibleWorkspaceIds()).thenThrow(new IllegalStateException("MySQL down"));

        assertThatThrownBy(() -> service.runNight(START)).isInstanceOf(IllegalStateException.class);

        assertThat(end().outcome()).isEqualTo(TopicNight.Outcome.FAILED);
    }

    @Test
    @DisplayName("a night whose record cannot start throws before it runs anything, and writes no end")
    void recordStartFails() {
        doThrow(new IllegalStateException("MySQL down")).when(nightDAO).deleteProjects(NIGHT);

        assertThatThrownBy(() -> service.runNight(START)).isInstanceOf(IllegalStateException.class);

        verify(rebuildService, never()).startRun(any(), any(), any());
        verify(nightDAO, never()).insertProjects(any());
        verify(nightDAO, never()).end(any());
    }

    @Test
    @DisplayName("a record write that fails is logged and the night goes on")
    void recordWriteFails() {
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any())).thenReturn(UUID.randomUUID());
        doThrow(new IllegalStateException("MySQL down")).when(nightDAO).insertProjects(any());

        var result = service.runNight(START);

        assertThat(result.saved()).isEqualTo(1);
        assertThat(meter.sum("topics.nightly.projects", "decision", "nightly_update")).isEqualTo(1);
        assertThat(end().outcome()).isEqualTo(TopicNight.Outcome.FINISHED);
    }

    @Test
    @DisplayName("housekeeping fails nights left running past the night's lease and deletes nights past the retention")
    void nightHousekeeping() {
        var before = Instant.now();

        service.runNight(START);

        var startedBefore = ArgumentCaptor.forClass(Instant.class);
        verify(nightDAO).failAbandoned(startedBefore.capture(), any());
        assertThat(startedBefore.getValue()).isBetween(before.minus(service.lease()),
                Instant.now().minus(service.lease()));
        var cutoff = LocalDate.ofInstant(before.minus(Duration.ofDays(90)), ZoneOffset.UTC);
        verify(nightDAO).deleteNightsBefore(cutoff);
        verify(nightDAO).deleteProjectsBefore(cutoff);
    }

    @Test
    @DisplayName("a project whose last 3 runs failed since 7 nights before the night is skipped as paused, and no run "
            + "starts")
    void paused() {
        when(runDAO.findPausedProjectIds(any(), any(), anyInt(), any())).thenReturn(Set.of(started));

        var result = service.runNight(START);

        assertThat(result.saved()).isZero();
        assertThat(result.skipped()).isEqualTo(5);
        verify(rebuildService, never()).startRun(any(), any(), any());
        assertThat(meter.sum("topics.nightly.projects", "decision", "skip", "reason", "paused", "workspace_id",
                WORKSPACE_ID)).isEqualTo(1);
        assertThat(rows()).filteredOn(row -> started.equals(row.projectId()))
                .extracting(TopicNightProject::decision, TopicNightProject::reason)
                .containsExactly(tuple("skip", "paused"));
        verify(runDAO).findPausedProjectIds(eq(WORKSPACE_ID), any(), eq(3), eq(Instant.parse("2026-10-26T00:00:00Z")));
    }

    @Test
    @DisplayName("the queue runs the stalest first: no state row or no window_until, then the oldest window_until; "
            + "among equals the fewest vectors")
    void queueOrder() {
        var newer = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 10);
        var older = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 500);
        var nullWindow = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 300);
        var noStateLarge = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 900);
        var noStateSmall = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 100);
        var windowUntil = new HashMap<UUID, Instant>();
        windowUntil.put(newer.projectId(), START.minus(Duration.ofDays(1)));
        windowUntil.put(older.projectId(), START.minus(Duration.ofDays(3)));
        windowUntil.put(nullWindow.projectId(), null);
        var queue = new ArrayList<>(List.of(newer, older, nullWindow, noStateLarge, noStateSmall));

        queue.sort(TopicsNightlyService.queueOrder(windowUntil));

        assertThat(queue).containsExactly(noStateSmall, nullWindow, noStateLarge, older, newer);
    }

    @SuppressWarnings("unchecked")
    private List<TopicNightProject> rows() {
        ArgumentCaptor<List<TopicNightProject>> rows = ArgumentCaptor.forClass(List.class);
        verify(nightDAO, atLeast(0)).insertProjects(rows.capture());
        return rows.getAllValues().stream().flatMap(List::stream).toList();
    }

    private TopicNight end() {
        var night = ArgumentCaptor.forClass(TopicNight.class);
        verify(nightDAO).end(night.capture());
        return night.getValue();
    }
}
