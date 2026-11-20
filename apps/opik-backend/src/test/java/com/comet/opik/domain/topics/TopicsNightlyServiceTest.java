package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One night with every store mocked: each project's decision with its reason, counted and written to
 * {@code topic_projects}, a failed count read, a run that cannot start, a paused project, a project with no topic left,
 * the night's outcome, and the queue order.
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
    private TopicProjectNightDAO nightDAO;
    private TopicsRebuildService rebuildService;
    private LockService lockService;
    private TopicsNightlyService service;
    private TopicsProjectRunner runner;
    private TopicsConfig config;
    private TopicWindowDAO windowDAO;

    @BeforeEach
    void setUp() {
        meter = new TopicsTestMeter();
        workspaceService = mock(TopicWorkspaceService.class);
        runDAO = mock(TopicRunDAO.class);
        topicDAO = mock(TopicDAO.class);
        nightDAO = mock(TopicProjectNightDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicRunDAO.class)).thenReturn(runDAO);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        when(handle.attach(TopicProjectNightDAO.class)).thenReturn(nightDAO);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));

        when(workspaceService.enabledWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID, BROKEN_WORKSPACE_ID));
        list(WORKSPACE_ID, small, 50);
        list(WORKSPACE_ID, deleted, 120);
        list(WORKSPACE_ID, built, 120);
        list(WORKSPACE_ID, locked, 130);
        list(WORKSPACE_ID, started, 140);
        list(BROKEN_WORKSPACE_ID, broken, 150);
        failedCounts.add(broken);
        when(topicDAO.findProjectsWithTopics(any())).thenAnswer(invocation -> listed.stream()
                .filter(key -> invocation.<Collection<String>>getArgument(0).contains(key.workspaceId())).toList());
        when(topicDAO.findProjectsWithTopics(anyString(), any()))
                .thenAnswer(invocation -> List.copyOf(invocation.<Collection<UUID>>getArgument(1)));
        // built's last saved run already covers the night: the night skips it under its lock.
        when(topicDAO.findProjects(eq(WORKSPACE_ID), any()))
                .thenAnswer(invocation -> invocation.<Collection<UUID>>getArgument(1).contains(built)
                        ? List.of(TopicProject.builder().projectId(built).workspaceId(WORKSPACE_ID)
                                .windowUntil(NIGHT.atStartOfDay(ZoneOffset.UTC).toInstant()).build())
                        : List.of());
        when(topicDAO.findExistingProjectIds(eq(WORKSPACE_ID), any())).thenAnswer(invocation -> {
            var ids = new HashSet<>(invocation.<Collection<UUID>>getArgument(1));
            ids.remove(deleted);
            return ids;
        });
        windowDAO = mock(TopicWindowDAO.class);
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
                        .status(TopicProject.RunStatus.SAVED).build());

        config = TestConfigUtils.loadConfigTest().getTopics();
        var metrics = new TopicsMetrics(meter.meter);
        runner = spy(new TopicsProjectRunner(config, template, rebuildService, lockService));
        service = new TopicsNightlyService(config, template, workspaceService, windowDAO, runner, metrics,
                lockService);
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
    @DisplayName("a count read that fails skips only its own project: another project of the workspace still runs")
    void countFailurePerProject() {
        var other = UUID.randomUUID();
        list(BROKEN_WORKSPACE_ID, other, 160);
        when(topicDAO.findExistingProjectIds(eq(BROKEN_WORKSPACE_ID), any()))
                .thenAnswer(invocation -> Set.copyOf(invocation.<Collection<UUID>>getArgument(1)));
        when(rebuildService.startRun(any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        service.runNight(START);

        verify(rebuildService).nightlyUpdate(any(), eq(BROKEN_WORKSPACE_ID), eq(other), eq(NIGHT));
        verify(rebuildService, never()).startRun(eq(BROKEN_WORKSPACE_ID), eq(broken), any(), any());
        assertThat(decisions(BROKEN_WORKSPACE_ID, "count_failed")).isEqualTo(1);
        assertThat(decisions(BROKEN_WORKSPACE_ID, "nightly_update")).isEqualTo(1);
        assertThat(looked(BROKEN_WORKSPACE_ID)).containsExactlyInAnyOrder(broken, other);
    }

    @Test
    @DisplayName("while another run on the pod holds the run slot past one run limit, the night skips the project as "
            + "locked before its lock and its attempt; once the slot is free, the project is updated and the slot is "
            + "given back")
    void runSlotHeld() {
        doReturn(Duration.ofMillis(50)).when(runner).runLimit();
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any(), any())).thenReturn(UUID.randomUUID());
        assertThat(runner.acquireRun(Duration.ZERO)).isTrue();
        try {
            assertThat(service.runNight(START).saved()).isZero();
        } finally {
            runner.releaseRun();
        }
        verify(rebuildService, never()).startRun(any(), any(), any(), any());
        verify(lockService, never()).lockUsingToken(eq(TopicsProjectRunner.projectLock(started)), any());
        // built, locked and started: every project the night queued.
        assertThat(skips("locked")).isEqualTo(3);
        verify(nightDAO).markLooked(WORKSPACE_ID, Set.of(started), NIGHT);

        assertThat(service.runNight(START).saved()).isEqualTo(1);
        assertThat(runner.acquireRun(Duration.ZERO)).isTrue();
    }

    @Test
    @DisplayName("a project whose lock is taken after the budget's end is not reached: no run starts, and its lock "
            + "and the run slot are given back")
    void lockTakenAfterBudgetEnd() {
        // The test owns the clock: it stands still until the project's lock is taken, then jumps past the budget.
        var clock = new AtomicReference<>(Instant.now());
        service.now = clock::get;
        config.getNightly().setBudget(io.dropwizard.util.Duration.minutes(10));
        doAnswer(invocation -> {
            clock.set(clock.get().plus(Duration.ofHours(1)));
            return Mono.just(true);
        }).when(lockService).lockUsingToken(eq(TopicsProjectRunner.projectLock(started)), any());

        var result = service.runNight(START);

        assertThat(result.saved()).isZero();
        verify(rebuildService, never()).startRun(eq(WORKSPACE_ID), eq(started), any(), any());
        verify(lockService).unlockUsingToken(TopicsProjectRunner.projectLock(started));
        // built is queued after started, so the night does not reach it either.
        assertThat(notReached(WORKSPACE_ID)).containsExactlyInAnyOrder(started, built);
        assertThat(runner.acquireRun(Duration.ZERO)).isTrue();
        runner.releaseRun();
    }

    @Test
    @DisplayName("the wait for a held run slot ends at the budget's end, not one run limit later: a project starts "
            + "within the budget, so the night's lease covers its run")
    void runSlotWaitEndsAtBudgetEnd() {
        assertThat(runner.runLimit()).isGreaterThan(Duration.ofMinutes(1));
        assertThat(runner.acquireRun(Duration.ZERO)).isTrue();
        try {
            long began = System.nanoTime();
            assertThat(runner.acquireRun(Instant.now().plusMillis(100))).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(5));
            assertThat(runner.acquireRun(Instant.now().minusSeconds(1))).isFalse();
        } finally {
            runner.releaseRun();
        }
    }

    @Test
    @DisplayName("a project whose topics are gone by the time its lock is taken is skipped as no_list")
    void noList() {
        when(topicDAO.findProjectsWithTopics(WORKSPACE_ID, Set.of(started))).thenReturn(List.of());

        var result = service.runNight(START);

        assertThat(result.saved()).isZero();
        verify(rebuildService, never()).startRun(any(), any(), any(), any());
        assertThat(skips("no_list")).isEqualTo(1);
        verify(nightDAO).markLooked(WORKSPACE_ID, Set.of(started), NIGHT);
    }

    @Test
    @DisplayName("a run that cannot start is counted once, as start_failed, and its decision is still counted and "
            + "written")
    void startFailure() {
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), eq(TopicProject.RunMode.NIGHTLY_UPDATE), any()))
                .thenThrow(new IllegalStateException("MySQL down"));

        var result = service.runNight(START);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("start_failed");
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
        });
        assertThat(decisions(WORKSPACE_ID, "nightly_update")).isEqualTo(1);
        verify(nightDAO).markLooked(WORKSPACE_ID, Set.of(started), NIGHT);
    }

    @Test
    @DisplayName("no window count starts after the budget's end: the projects left are not reached, so the scan ends "
            + "within the night lock's lease")
    void countScanStopsAtBudgetEnd() {
        config.getNightly().setBudget(io.dropwizard.util.Duration.milliseconds(0));

        var result = service.runNight(START);

        assertThat(result.notReached()).isEqualTo(listed.size());
        verify(windowDAO, never()).countByProject(anyString(), any(), any(), any(), anyLong());
        verify(rebuildService, never()).startRun(any(), any(), any(), any());
        assertThat(notReached(WORKSPACE_ID)).containsExactlyInAnyOrder(small, deleted, built, locked, started);
        assertThat(notReached(BROKEN_WORKSPACE_ID)).containsExactly(broken);
        assertThat(looked(WORKSPACE_ID)).isEmpty();
        assertThat(meter.sum(TopicsMetrics.NIGHTLY_DECISIONS, "decision", "not_reached")).isEqualTo(listed.size());
    }

    @Test
    @DisplayName("projects the night does not reach add one to their not_reached_nights, in one write per workspace")
    void notReached() {
        Thread.currentThread().interrupt();
        try {
            var result = service.runNight(START);

            assertThat(result.notReached()).isEqualTo(3);
        } finally {
            Thread.interrupted();
        }
        assertThat(meter.points("topics.runs")).isEmpty();
        assertThat(notReached(WORKSPACE_ID)).containsExactlyInAnyOrder(built, locked, started);
        verify(nightDAO, times(1)).markNotReached(eq(WORKSPACE_ID), any());
        assertThat(decisions(WORKSPACE_ID, "not_reached")).isEqualTo(3);
    }

    @Test
    @DisplayName("each project's decision is counted by decision and skip reason, with its workspace, and the projects "
            + "the night reached get its date; every series starts at 0, and the night is counted finished")
    void decisions() {
        var runId = UUID.randomUUID();
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), eq(TopicProject.RunMode.NIGHTLY_UPDATE), any()))
                .thenReturn(runId);

        var result = service.runNight(START);

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(4);
        verify(rebuildService).nightlyUpdate(runId, WORKSPACE_ID, started, NIGHT);
        verify(rebuildService, never()).firstBuild(any(), any(), any(), any());
        assertThat(meter.points("topics.runs")).isEmpty();
        assertThat(decisions(BROKEN_WORKSPACE_ID, "count_failed")).isEqualTo(1);
        assertThat(decisions(WORKSPACE_ID, "nightly_update")).isEqualTo(1);
        assertThat(decisions(WORKSPACE_ID, "not_reached")).isZero();
        for (var reason : List.of("small", "deleted", "already_saved", "locked")) {
            assertThat(skips(reason)).as(reason).isEqualTo(1);
        }
        assertThat(skips("paused")).isZero();
        assertThat(meter.points(TopicsMetrics.NIGHTLY_DECISIONS))
                .filteredOn(point -> WORKSPACE_ID.equals(point.get("workspace_id"))
                        && "skip".equals(point.get("decision")) && "no_list".equals(point.get("reason")))
                .singleElement().satisfies(point -> assertThat(point.value()).isZero());
        assertThat(looked(WORKSPACE_ID)).containsExactlyInAnyOrder(small, deleted, built, locked, started);
        assertThat(looked(BROKEN_WORKSPACE_ID)).containsExactly(broken);
        verify(nightDAO, never()).markNotReached(any(), any());
        assertThat(meter.sum(TopicsMetrics.NIGHTLY_NIGHTS, "outcome", "finished")).isEqualTo(1);
        assertThat(meter.points(TopicsMetrics.NIGHTLY_NIGHTS))
                .filteredOn(point -> "failed".equals(point.get("outcome")))
                .singleElement().satisfies(point -> assertThat(point.value()).isZero());
    }

    @Test
    @DisplayName("a night with no workspace is counted finished and writes nothing")
    void emptyNight() {
        when(workspaceService.enabledWorkspaceIds()).thenReturn(List.of());

        service.runNight(START);

        verify(nightDAO, never()).markLooked(any(), any(), any());
        verify(nightDAO, never()).markNotReached(any(), any());
        assertThat(meter.sum(TopicsMetrics.NIGHTLY_NIGHTS, "outcome", "finished")).isEqualTo(1);
    }

    @Test
    @DisplayName("a night whose read of the workspaces fails is counted failed, and still throws")
    void failedNight() {
        when(workspaceService.enabledWorkspaceIds()).thenThrow(new IllegalStateException("MySQL down"));

        assertThatThrownBy(() -> service.runNight(START)).isInstanceOf(IllegalStateException.class);

        assertThat(meter.sum(TopicsMetrics.NIGHTLY_NIGHTS, "outcome", "failed")).isEqualTo(1);
        assertThat(meter.sum(TopicsMetrics.NIGHTLY_NIGHTS, "outcome", "finished")).isZero();
    }

    @Test
    @DisplayName("a decision write that fails is logged and the night goes on")
    void decisionWriteFails() {
        when(rebuildService.startRun(eq(WORKSPACE_ID), eq(started), any(), any())).thenReturn(UUID.randomUUID());
        when(nightDAO.markLooked(any(), any(), any())).thenThrow(new IllegalStateException("MySQL down"));

        var result = service.runNight(START);

        assertThat(result.saved()).isEqualTo(1);
        assertThat(decisions(WORKSPACE_ID, "nightly_update")).isEqualTo(1);
        assertThat(meter.sum(TopicsMetrics.NIGHTLY_NIGHTS, "outcome", "finished")).isEqualTo(1);
    }

    @Test
    @DisplayName("a project whose last 3 attempts failed since 7 nights before the night is skipped as paused, and no "
            + "run starts; an attempt left running past the lease counts as failed")
    void paused() {
        when(runDAO.findPausedProjectIds(any(), any(), anyInt(), any(), any())).thenReturn(Set.of(started));
        var before = Instant.now();

        var result = service.runNight(START);

        assertThat(result.saved()).isZero();
        assertThat(result.skipped()).isEqualTo(5);
        verify(rebuildService, never()).startRun(any(), any(), any(), any());
        assertThat(skips("paused")).isEqualTo(1);
        var abandonedBefore = ArgumentCaptor.forClass(Instant.class);
        verify(runDAO).findPausedProjectIds(eq(WORKSPACE_ID), any(), eq(3),
                eq(Instant.parse("2026-10-26T00:00:00Z")), abandonedBefore.capture());
        assertThat(abandonedBefore.getValue()).isBetween(before.minus(runner.projectLease()),
                Instant.now().minus(runner.projectLease()));
    }

    @Test
    @DisplayName("the queue runs the stalest first: no window_until, then the oldest window_until; among equals the "
            + "fewest vectors")
    void queueOrder() {
        var newer = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 10);
        var older = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 500);
        var nullWindow = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 300);
        var noRowLarge = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 900);
        var noRowSmall = new TopicsNightlyService.Candidate(WORKSPACE_ID, UUID.randomUUID(), 100);
        var windowUntil = new HashMap<UUID, Instant>();
        windowUntil.put(newer.projectId(), START.minus(Duration.ofDays(1)));
        windowUntil.put(older.projectId(), START.minus(Duration.ofDays(3)));
        windowUntil.put(nullWindow.projectId(), null);
        var queue = new ArrayList<>(List.of(newer, older, nullWindow, noRowLarge, noRowSmall));

        queue.sort(TopicsNightlyService.queueOrder(windowUntil));

        assertThat(queue).containsExactly(noRowSmall, nullWindow, noRowLarge, older, newer);
    }

    private double decisions(String workspaceId, String decision) {
        return meter.sum(TopicsMetrics.NIGHTLY_DECISIONS, "workspace_id", workspaceId, "decision", decision);
    }

    private double skips(String reason) {
        return meter.sum(TopicsMetrics.NIGHTLY_DECISIONS, "workspace_id", WORKSPACE_ID, "decision", "skip", "reason",
                reason);
    }

    /** The workspace's projects the nights marked as looked at, over every write. */
    @SuppressWarnings("unchecked")
    private List<UUID> looked(String workspaceId) {
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(nightDAO, atLeast(0)).markLooked(eq(workspaceId), ids.capture(), eq(NIGHT));
        return ids.getAllValues().stream().flatMap(Collection::stream).toList();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> notReached(String workspaceId) {
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(nightDAO, atLeast(0)).markNotReached(eq(workspaceId), ids.capture());
        return ids.getAllValues().stream().flatMap(Collection::stream).toList();
    }
}
