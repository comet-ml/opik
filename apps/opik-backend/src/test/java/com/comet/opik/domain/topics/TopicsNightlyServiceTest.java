package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.util.concurrent.Uninterruptibles;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a night counts and records, with every store mocked: each project's decision with its reason, a failed count
 * read, a run that cannot start, the runs housekeeping finds abandoned, a paused project, and the night's record. Also
 * the queue order, and the first-build job's projects and order.
 */
@DisplayName("topics nightly service metrics")
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

    private TopicsTestMeter meter;
    private TopicWorkspaceService workspaceService;
    private TopicRunDAO runDAO;
    private TopicDAO topicDAO;
    private TopicNightDAO nightDAO;
    private TopicWindowDAO windowDAO;
    private TopicsRebuildService rebuildService;
    private LockService lockService;
    private TopicsConfig config;
    private TopicsNightlyService service;

    @BeforeEach
    void setUp() throws Exception {
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
        when(topicDAO.findStates(eq(WORKSPACE_ID), any()))
                .thenAnswer(invocation -> invocation.<Collection<UUID>>getArgument(1)
                        .contains(built)
                                ? List.of(TopicProjectState.builder().projectId(built).workspaceId(WORKSPACE_ID)
                                        .build())
                                : List.of());
        when(topicDAO.findExistingProjectIds(eq(WORKSPACE_ID), any())).thenAnswer(invocation -> {
            var ids = new HashSet<>(invocation.<Collection<UUID>>getArgument(1));
            ids.remove(deleted);
            return ids;
        });
        windowDAO = mock(TopicWindowDAO.class);
        when(windowDAO.countByProject(eq(WORKSPACE_ID), isNull(), any(), any(), anyLong())).thenReturn(Flux.just(
                new TopicWindowDAO.ProjectCount(small, 50),
                new TopicWindowDAO.ProjectCount(deleted, 120),
                new TopicWindowDAO.ProjectCount(built, 120),
                new TopicWindowDAO.ProjectCount(locked, 130),
                new TopicWindowDAO.ProjectCount(started, 140)));
        when(windowDAO.countByProject(eq(BROKEN_WORKSPACE_ID), isNull(), any(), any(), anyLong()))
                .thenReturn(Flux.error(new IllegalStateException("ClickHouse guard tripped")));

        lockService = mock(LockService.class);
        when(lockService.lockUsingToken(any(), any())).thenAnswer(invocation -> Mono.just(
                !invocation.<LockService.Lock>getArgument(0).equals(TopicsNightlyService.projectLock(locked))));
        when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());

        rebuildService = mock(TopicsRebuildService.class);
        when(rebuildService.firstBuild(any(), anyString(), any(), any()))
                .thenAnswer(invocation -> TopicsRebuildService.Outcome.builder().runId(invocation.getArgument(0))
                        .status(TopicRun.Status.SAVED).build());

        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());

        config = TestConfigUtils.loadConfigTest().getTopics();
        service = new TopicsNightlyService(config, template, workspaceService,
                windowDAO, rebuildService, new TopicsMetrics(meter.meter), lockService, idGenerator);
    }

    @Test
    @DisplayName("a build that throws fails its run with internal, and the night counts it failed")
    void buildThatThrowsIsInternal() {
        var runId = UUID.randomUUID();
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any())).thenReturn(runId);
        doThrow(new IllegalStateException("a bug in the build"))
                .when(rebuildService).firstBuild(eq(runId), anyString(), any(), any());
        when(rebuildService.fail(any(), anyString(), any(), anyString(), any())).thenReturn(true);

        var result = service.runNight(START);

        assertThat(result.saved()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        verify(rebuildService).fail(eq(runId), eq(WORKSPACE_ID), eq(TopicsRunException.Code.INTERNAL),
                contains("a bug in the build"), isNull());
    }

    @Test
    @DisplayName("a run whose timeout comes after its save counts as saved, not failed")
    void timeoutAfterSaveIsSaved() {
        var runId = UUID.randomUUID();
        config.getNightly().setProjectTimeout(io.dropwizard.util.Duration.milliseconds(200));
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any())).thenReturn(runId);
        // The commit is done; the trace_topics insert outlasts the timeout and goes on through the interrupt.
        doAnswer(invocation -> {
            Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(600));
            return TopicsRebuildService.Outcome.builder().runId(runId).status(TopicRun.Status.SAVED).build();
        }).when(rebuildService).firstBuild(eq(runId), anyString(), any(), any());
        when(rebuildService.fail(any(), anyString(), any(), anyString(), any())).thenReturn(false);
        when(runDAO.findById(WORKSPACE_ID, runId)).thenReturn(TopicRun.builder().runId(runId)
                .workspaceId(WORKSPACE_ID).projectId(started).mode(TopicRun.Mode.FIRST_BUILD)
                .status(TopicRun.Status.SAVED).startedAt(START).build());

        var result = service.runNight(START);

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(rebuildService).fail(eq(runId), eq(WORKSPACE_ID), eq(TopicsRunException.Code.TIMEOUT), anyString(),
                isNull());
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
        assertThat(meter.sum("topics.nightly.projects", "decision", "first_build", "workspace_id", WORKSPACE_ID))
                .isEqualTo(1);
        assertThat(meter.points("topics.nightly.count_failures")).singleElement().satisfies(point -> {
            assertThat(point.get("workspace_id")).isEqualTo(BROKEN_WORKSPACE_ID);
            assertThat(point.get("error_type")).isNotBlank();
        });
        assertThat(meter.points("topics.runs")).isEmpty();
    }

    @Test
    @DisplayName("a run that cannot start is counted once, as start_failed, and its decision is still counted")
    void startFailure() {
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any()))
                .thenThrow(new IllegalStateException("MySQL down"));

        var result = service.runNight(START);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("start_failed");
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
        });
        assertThat(meter.sum("topics.nightly.projects", "decision", "first_build")).isEqualTo(1);
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
    @DisplayName("projects the night does not reach are counted per workspace")
    void notReached() {
        Thread.currentThread().interrupt();
        try {
            var result = service.runNight(START);

            assertThat(result.notReached()).isEqualTo(2);
        } finally {
            Thread.interrupted();
        }
        assertThat(meter.sum("topics.nightly.projects", "decision", "not_reached", "workspace_id", WORKSPACE_ID))
                .isEqualTo(2);
        assertThat(meter.points("topics.runs")).isEmpty();
    }

    @Test
    @DisplayName("the night's record: a row per project with its decision, reason and run, and the night's counts")
    void nightRecord() {
        var runId = UUID.randomUUID();
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any())).thenReturn(runId);

        service.runNight(START);

        verify(nightDAO).start(eq(NIGHT), any());
        verify(nightDAO).deleteProjects(NIGHT);
        assertThat(rows()).extracting(TopicNightProject::projectId, TopicNightProject::decision,
                TopicNightProject::reason, TopicNightProject::runId)
                .containsExactlyInAnyOrder(
                        tuple(null, "count_failed", null, null),
                        tuple(small, "skip", "small", null),
                        tuple(deleted, "skip", "deleted", null),
                        tuple(built, "skip", "already_saved", null),
                        tuple(locked, "skip", "locked", null),
                        tuple(started, "first_build", null, runId));
        assertThat(rows()).allSatisfy(row -> assertThat(row.night()).isEqualTo(NIGHT));
        assertThat(end()).satisfies(night -> {
            assertThat(night.outcome()).isEqualTo(TopicNight.Outcome.FINISHED);
            assertThat(night.eligibleWorkspaces()).isEqualTo(2);
            assertThat(night.countFailures()).isEqualTo(1);
            assertThat(night.projects()).isEqualTo("{\"first_build\":1,\"skip\":4}");
        });
    }

    @Test
    @DisplayName("a night with no workspace still writes its record, finished with no project")
    void emptyNight() {
        when(workspaceService.eligibleWorkspaceIds()).thenReturn(List.of());

        service.runNight(START);

        verify(nightDAO).start(eq(NIGHT), any());
        verify(nightDAO, never()).insertProjects(any());
        assertThat(end()).satisfies(night -> {
            assertThat(night.outcome()).isEqualTo(TopicNight.Outcome.FINISHED);
            assertThat(night.eligibleWorkspaces()).isZero();
            assertThat(night.projects()).isEqualTo("{}");
        });
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
        assertThat(meter.sum("topics.nightly.projects", "decision", "first_build")).isEqualTo(1);
        assertThat(end().outcome()).isEqualTo(TopicNight.Outcome.FINISHED);
    }

    @Test
    @DisplayName("housekeeping fails nights left running past the night's lease and deletes nights past the retention")
    void nightHousekeeping() {
        var before = Instant.now();

        service.runNight(START);

        var startedBefore = ArgumentCaptor.forClass(Instant.class);
        verify(nightDAO).failAbandoned(startedBefore.capture(), any());
        assertThat(startedBefore.getValue()).isBetween(before.minus(service.lease()), Instant.now()
                .minus(service.lease()));
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
    @DisplayName("the queue runs the stalest first: no list, then the oldest window_until; among equals the fewest "
            + "vectors")
    void queueOrder() {
        var newer = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 10);
        var older = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 500);
        var nullWindow = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 300);
        var noListLarge = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 900);
        var noListSmall = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 100);
        var windowUntil = new HashMap<UUID, Instant>();
        windowUntil.put(newer.projectId(), START.minus(Duration.ofDays(1)));
        windowUntil.put(older.projectId(), START.minus(Duration.ofDays(3)));
        windowUntil.put(nullWindow.projectId(), null);
        var queue = new ArrayList<>(List.of(newer, older, nullWindow, noListLarge, noListSmall));

        queue.sort(TopicsNightlyService.queueOrder(windowUntil));

        assertThat(queue).containsExactly(noListSmall, nullWindow, noListLarge, older, newer);
    }

    @Test
    @DisplayName("first builds: the projects with minVectors in tomorrow's window that exist, have no list, are not "
            + "paused and had no run in the last 24 h, the most vectors first")
    void firstBuildCandidates() {
        var otherWorkspaceId = "ws-2";
        var recent = UUID.randomUUID();
        var paused = UUID.randomUUID();
        var largest = UUID.randomUUID();
        var otherWorkspace = UUID.randomUUID();
        var tiedLow = new UUID(0, 1);
        var tiedHigh = new UUID(0, 2);
        when(workspaceService.eligibleWorkspaceIds())
                .thenReturn(List.of(WORKSPACE_ID, BROKEN_WORKSPACE_ID, otherWorkspaceId));
        when(windowDAO.countByProject(eq(WORKSPACE_ID), isNull(), any(), any(), anyLong())).thenReturn(Flux.just(
                new TopicWindowDAO.ProjectCount(small, 99),
                new TopicWindowDAO.ProjectCount(deleted, 900),
                new TopicWindowDAO.ProjectCount(built, 900),
                new TopicWindowDAO.ProjectCount(recent, 900),
                new TopicWindowDAO.ProjectCount(paused, 900),
                new TopicWindowDAO.ProjectCount(tiedHigh, 100),
                new TopicWindowDAO.ProjectCount(largest, 500),
                new TopicWindowDAO.ProjectCount(tiedLow, 100)));
        when(windowDAO.countByProject(eq(otherWorkspaceId), isNull(), any(), any(), anyLong()))
                .thenReturn(Flux.just(new TopicWindowDAO.ProjectCount(otherWorkspace, 300)));
        when(topicDAO.findExistingProjectIds(eq(otherWorkspaceId), any()))
                .thenAnswer(invocation -> Set.copyOf(invocation.<Collection<UUID>>getArgument(1)));
        when(runDAO.findStartedSince(any(), any(), any())).thenReturn(Set.of(recent));
        when(runDAO.findPausedProjectIds(any(), any(), anyInt(), any())).thenReturn(Set.of(paused));
        var tomorrow = NIGHT.plusDays(1);

        var candidates = service.firstBuildCandidates(tomorrow, START);

        assertThat(candidates).extracting(TopicsNightlyService.Candidate::workspaceId,
                TopicsNightlyService.Candidate::projectId, TopicsNightlyService.Candidate::vectors)
                .containsExactly(
                        tuple(WORKSPACE_ID, largest, 500L),
                        tuple(otherWorkspaceId, otherWorkspace, 300L),
                        tuple(WORKSPACE_ID, tiedLow, 100L),
                        tuple(WORKSPACE_ID, tiedHigh, 100L));
        verify(windowDAO).countByProject(eq(WORKSPACE_ID), isNull(), eq(Instant.parse("2026-10-04T00:00:00Z")),
                eq(Instant.parse("2026-11-03T00:00:00Z")), anyLong());
        verify(runDAO).findStartedSince(eq(WORKSPACE_ID), any(), eq(START.minus(Duration.ofHours(24))));
        verify(runDAO).findPausedProjectIds(eq(WORKSPACE_ID), any(), eq(3), eq(Instant.parse("2026-10-27T00:00:00Z")));
        verify(nightDAO, never()).insertProjects(any());
    }

    @Test
    @DisplayName("first builds start no run while the night holds its lock, and do not read the projects")
    void firstBuildsSkipWhileTheNightRuns() {
        when(lockService.isLocked(TopicsNightlyService.NIGHT_LOCK)).thenReturn(Mono.just(true));

        service.runFirstBuilds(START);

        verify(workspaceService, never()).eligibleWorkspaceIds();
        verify(rebuildService, never()).startRun(any(), any(), any());
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
