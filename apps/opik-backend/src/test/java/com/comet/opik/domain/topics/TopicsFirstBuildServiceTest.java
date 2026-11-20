package com.comet.opik.domain.topics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.error.ConflictException;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.util.concurrent.Uninterruptibles;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first-build job's run and a run by hand, with every store mocked: which projects are built and in what order,
 * the backfill gate, the retries of a failed build, a run that cannot start or throws, a timeout after the save, the
 * locks, and what is counted.
 */
@DisplayName("topics first-build service")
class TopicsFirstBuildServiceTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final String OTHER_WORKSPACE_ID = "ws-2";
    private static final Instant START = Instant.parse("2026-11-02T02:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 3);
    private static final Instant FROM = Instant.parse("2026-10-04T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-03T00:00:00Z");

    private final UUID started = UUID.randomUUID();
    private final UUID next = UUID.randomUUID();

    private TopicsTestMeter meter;
    private TopicWorkspaceService workspaceService;
    private TopicDAO topicDAO;
    private final List<TopicDAO.ProjectKey> listless = new ArrayList<>();
    private final Map<UUID, Long> vectors = new HashMap<>();
    /** The projects' {@code topic_projects} rows, as {@code findProjects} reads them. */
    private final Map<UUID, TopicProject> rows = new ConcurrentHashMap<>();
    private final Set<UUID> withList = Collections.synchronizedSet(new HashSet<>());
    private TopicVectorBackfillDAO backfillDAO;
    private final List<TopicVectorBackfillDAO.Backfill> backfills = new ArrayList<>();
    private final Set<String> checkedWorkspaces = new HashSet<>();
    private TopicWindowDAO windowDAO;
    private TopicsRebuildService rebuildService;
    private TopicWindowLabelService labelService;
    private final List<TopicDAO.ProjectKey> unlabelled = new ArrayList<>();
    private LockService lockService;
    private TopicsConfig config;
    private TopicsProjectRunner runner;
    private TopicsFirstBuildService service;

    @BeforeEach
    void setUp() {
        meter = new TopicsTestMeter();
        workspaceService = mock(TopicWorkspaceService.class);
        topicDAO = mock(TopicDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        // The vectors tick has just checked both workspaces, so a project with no backfill of its own is open.
        backfillDAO = mock(TopicVectorBackfillDAO.class);
        when(handle.attach(TopicVectorBackfillDAO.class)).thenReturn(backfillDAO);
        checkedWorkspaces.addAll(List.of(WORKSPACE_ID, OTHER_WORKSPACE_ID));
        when(backfillDAO.findByWorkspaceIds(any())).thenAnswer(invocation -> backfills.stream()
                .filter(row -> invocation.<Collection<String>>getArgument(0).contains(row.workspaceId())).toList());
        when(backfillDAO.findWorkspaces(any())).thenAnswer(invocation -> checkedWorkspaces.stream()
                .filter(invocation.<Collection<String>>getArgument(0)::contains)
                .map(workspaceId -> TopicVectorBackfillDAO.Workspace.builder().workspaceId(workspaceId)
                        .vectorsCheckedAt(Instant.now()).build())
                .toList());
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));

        when(workspaceService.enabledWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID));
        when(workspaceService.isEnabled(WORKSPACE_ID)).thenReturn(true);
        when(topicDAO.findListlessProjects(any(), any())).thenAnswer(invocation -> listless.stream()
                .filter(key -> invocation.<Collection<String>>getArgument(0).contains(key.workspaceId())).toList());
        when(topicDAO.findLabelsIncomplete(any())).thenAnswer(invocation -> unlabelled.stream()
                .filter(key -> invocation.<Collection<String>>getArgument(0).contains(key.workspaceId())).toList());
        when(topicDAO.findProjects(anyString(), any())).thenAnswer(invocation -> invocation
                .<Collection<UUID>>getArgument(1).stream()
                .map(projectId -> withList.contains(projectId)
                        ? Optional.ofNullable(rows.get(projectId)).orElseGet(() -> row(projectId).build())
                                .toBuilder().listRunId(UUID.randomUUID()).build()
                        : rows.get(projectId))
                .filter(Objects::nonNull)
                .toList());
        windowDAO = mock(TopicWindowDAO.class);
        when(windowDAO.countByProject(anyString(), any(), any(), any(), anyLong())).thenAnswer(invocation -> Flux
                .fromIterable(invocation.<Collection<UUID>>getArgument(1))
                .filter(vectors::containsKey)
                .map(projectId -> new TopicWindowDAO.ProjectCount(projectId, vectors.get(projectId))));

        lockService = mock(LockService.class);
        when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(true));
        when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());

        rebuildService = mock(TopicsRebuildService.class);
        when(rebuildService.startRun(anyString(), any(), any(), any())).thenAnswer(invocation -> UUID.randomUUID());
        when(rebuildService.firstBuild(any(), anyString(), any(), any()))
                .thenAnswer(invocation -> TopicsRebuildService.Outcome.builder().runId(invocation.getArgument(0))
                        .status(TopicProject.RunStatus.SAVED).build());

        labelService = mock(TopicWindowLabelService.class);
        when(labelService.labelWindow(anyString(), any(), any(), any()))
                .thenReturn(TopicWindowLabelService.Pass.builder().complete(true).build());

        config = TestConfigUtils.loadConfigTest().getTopics();
        var metrics = new TopicsMetrics(meter.meter);
        runner = spy(new TopicsProjectRunner(config, template, rebuildService, lockService));
        service = new TopicsFirstBuildService(config, template, workspaceService, windowDAO, rebuildService, runner,
                metrics, new TopicsBackfillGate(config, template), labelService);
    }

    @Test
    @DisplayName("the projects with no list, a trace in tomorrow's window and minVectors in it, the most vectors first; "
            + "ClickHouse counts each of those alone, and a project whose count fails is left for the next run")
    void candidates() {
        var small = UUID.randomUUID();
        var largest = UUID.randomUUID();
        var otherWorkspace = UUID.randomUUID();
        var tiedLow = new UUID(0, 1);
        var tiedHigh = new UUID(0, 2);
        var broken = UUID.randomUUID();
        var besideBroken = UUID.randomUUID();
        when(workspaceService.enabledWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID, OTHER_WORKSPACE_ID, "ws-3"));
        project(WORKSPACE_ID, small, 99);
        project(WORKSPACE_ID, tiedHigh, 100);
        project(WORKSPACE_ID, largest, 500);
        project(WORKSPACE_ID, tiedLow, 100);
        project(OTHER_WORKSPACE_ID, otherWorkspace, 300);
        project("ws-3", broken, 900);
        project("ws-3", besideBroken, 200);
        checkedWorkspaces.add("ws-3");
        when(windowDAO.countByProject(eq("ws-3"), argThat(ids -> ids.contains(broken)), any(), any(), anyLong()))
                .thenReturn(Flux.error(new IllegalStateException("ClickHouse guard tripped")));

        var candidates = service.candidates(NIGHT, START);

        assertThat(candidates).extracting(TopicsFirstBuildService.Candidate::workspaceId,
                TopicsFirstBuildService.Candidate::projectId, TopicsFirstBuildService.Candidate::vectors)
                .containsExactly(
                        tuple(WORKSPACE_ID, largest, 500L),
                        tuple(OTHER_WORKSPACE_ID, otherWorkspace, 300L),
                        tuple("ws-3", besideBroken, 200L),
                        tuple(WORKSPACE_ID, tiedLow, 100L),
                        tuple(WORKSPACE_ID, tiedHigh, 100L));
        verify(topicDAO).findListlessProjects(argThat(ids -> Set.copyOf(ids)
                .equals(Set.of(WORKSPACE_ID, OTHER_WORKSPACE_ID, "ws-3"))), eq(FROM));
        for (var projectId : List.of(small, tiedHigh, largest, tiedLow)) {
            verify(windowDAO).countByProject(eq(WORKSPACE_ID), eq(Set.of(projectId)), eq(FROM), eq(TO), anyLong());
        }
    }

    @Test
    @DisplayName("with no project without a list, a run reads no ClickHouse and starts nothing")
    void nothingToBuildReadsNoClickHouse() {
        service.run(START);

        verify(windowDAO, never()).countByProject(any(), any(), any(), any(), anyLong());
        verify(rebuildService, never()).startRun(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a project waits 1 h after its first failed build, 6 h after its second and a day after each later "
            + "one; one whose first failure started a day ago counts as waiting for its list")
    void retries() {
        assertThat(TopicsFirstBuildService.retryDelay(1)).isEqualTo(Duration.ofHours(1));
        assertThat(TopicsFirstBuildService.retryDelay(2)).isEqualTo(Duration.ofHours(6));
        assertThat(TopicsFirstBuildService.retryDelay(3)).isEqualTo(Duration.ofDays(1));
        assertThat(TopicsFirstBuildService.retryDelay(9)).isEqualTo(Duration.ofDays(1));

        failed(1, Duration.ofMinutes(50), Duration.ofMinutes(50));
        var onceDue = failed(1, Duration.ofMinutes(70), Duration.ofMinutes(70));
        failed(2, Duration.ofHours(5), Duration.ofHours(8));
        var twiceDue = failed(2, Duration.ofHours(7), Duration.ofHours(9));
        failed(5, Duration.ofHours(23), Duration.ofDays(4));
        var oftenDue = failed(5, Duration.ofHours(25), Duration.ofDays(5));
        when(workspaceService.enabledWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID, OTHER_WORKSPACE_ID));

        assertThat(service.candidates(NIGHT, START)).extracting(TopicsFirstBuildService.Candidate::projectId)
                .containsExactlyInAnyOrder(onceDue, twiceDue, oftenDue);
        // Their first failures started 8 h, 9 h, 4 days and 5 days ago: the last two wait for their list.
        assertThat(meter.observe(TopicsMetrics.FIRST_BUILD_WAITING))
                .extracting(point -> point.get("workspace_id"), TopicsTestMeter.Point::value)
                .containsExactlyInAnyOrder(tuple(WORKSPACE_ID, 2.0), tuple(OTHER_WORKSPACE_ID, 0.0));
    }

    @Test
    @DisplayName("an attempt left running past a lock's lease counts as a failed build that ended at its start; one "
            + "still within the lease is no failure")
    void abandonedAttemptCountsAsAFailure() {
        var lease = runner.projectLease();
        assertThat(lease).isLessThan(Duration.ofMinutes(50));
        var live = running(lease.minusMinutes(1));
        var abandonedWaits = running(Duration.ofMinutes(50));
        var abandonedDue = running(Duration.ofMinutes(70));

        assertThat(service.candidates(NIGHT, START)).extracting(TopicsFirstBuildService.Candidate::projectId)
                .containsExactlyInAnyOrder(live, abandonedDue)
                .doesNotContain(abandonedWaits);
    }

    @Test
    @DisplayName("a project whose vectors backfill has not drained is left out and not counted in ClickHouse; another "
            + "project of the workspace with a drained row is listed; once its row drains it is listed too")
    void waitsForTheBackfill() {
        var waiting = UUID.randomUUID();
        var drained = UUID.randomUUID();
        var noRow = UUID.randomUUID();
        project(WORKSPACE_ID, waiting, 2300);
        project(WORKSPACE_ID, drained, 400);
        project(WORKSPACE_ID, noRow, 150);
        backfills.add(backfill(WORKSPACE_ID, waiting).backfillStartedAt(START.minus(Duration.ofHours(5)))
                .backfillTracesTotal(90_000).backfillTracesDone(2300).build());
        backfills.add(backfill(WORKSPACE_ID, drained).backfillDrainedAt(START).build());

        assertThat(service.candidates(NIGHT, START)).extracting(TopicsFirstBuildService.Candidate::projectId)
                .containsExactly(drained, noRow);
        verify(windowDAO).countByProject(eq(WORKSPACE_ID), eq(Set.of(drained)), any(), any(), anyLong());
        verify(windowDAO).countByProject(eq(WORKSPACE_ID), eq(Set.of(noRow)), any(), any(), anyLong());
        verify(windowDAO, never()).countByProject(eq(WORKSPACE_ID), eq(Set.of(waiting)), any(), any(), anyLong());
        assertThat(meter.sum(TopicsMetrics.FIRST_BUILD_GATE, "outcome", "waiting", "workspace_id", WORKSPACE_ID))
                .isEqualTo(1);
        assertThat(meter.sum(TopicsMetrics.FIRST_BUILD_GATE, "outcome", "open", "workspace_id", WORKSPACE_ID))
                .isEqualTo(2);
        assertThat(meter.observe(TopicsMetrics.FIRST_BUILD_LONGEST_WAIT)).singleElement().satisfies(point -> {
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.value()).isEqualTo(Duration.ofHours(5).toSeconds());
        });

        backfills.removeIf(row -> row.projectId().equals(waiting));
        backfills.add(backfill(WORKSPACE_ID, waiting).backfillDrainedAt(START).backfillTracesTotal(90_000)
                .backfillTracesDone(88_000).build());

        assertThat(service.candidates(NIGHT, START)).extracting(TopicsFirstBuildService.Candidate::projectId)
                .containsExactly(waiting, drained, noRow);
        assertThat(meter.observe(TopicsMetrics.FIRST_BUILD_LONGEST_WAIT)).singleElement()
                .satisfies(point -> assertThat(point.value()).isZero());
    }

    @Test
    @DisplayName("past backfillMaxWait a project with 99% of its traces embedded is listed as timed_out_open; one "
            + "with fewer keeps waiting, is counted timed_out_waiting and warned about once per workspace")
    void pastTheMaxWait() {
        var nearlyDone = UUID.randomUUID();
        var stuck = UUID.randomUUID();
        var stuckToo = UUID.randomUUID();
        project(WORKSPACE_ID, nearlyDone, 990);
        project(WORKSPACE_ID, stuck, 500);
        project(WORKSPACE_ID, stuckToo, 300);
        backfills.add(backfill(WORKSPACE_ID, nearlyDone).backfillStartedAt(START.minus(Duration.ofHours(80)))
                .backfillTracesTotal(1000).backfillTracesDone(990).build());
        backfills.add(backfill(WORKSPACE_ID, stuck).backfillStartedAt(START.minus(Duration.ofHours(90)))
                .backfillTracesTotal(1000).backfillTracesDone(500).build());
        backfills.add(backfill(WORKSPACE_ID, stuckToo).backfillStartedAt(START.minus(Duration.ofHours(75))).build());
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(TopicsFirstBuildService.class);
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(service.candidates(NIGHT, START)).extracting(TopicsFirstBuildService.Candidate::projectId)
                    .containsExactly(nearlyDone);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(meter.sum(TopicsMetrics.FIRST_BUILD_GATE, "outcome", "timed_out_open")).isEqualTo(1);
        assertThat(meter.sum(TopicsMetrics.FIRST_BUILD_GATE, "outcome", "timed_out_waiting")).isEqualTo(2);
        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("workspace '" + WORKSPACE_ID + "'", "projects '2'",
                                "longest wait seconds '" + Duration.ofHours(90).toSeconds() + "'"));
        assertThat(meter.observe(TopicsMetrics.FIRST_BUILD_LONGEST_WAIT)).singleElement()
                .satisfies(point -> assertThat(point.value()).isEqualTo(Duration.ofHours(90).toSeconds()));
    }

    @Test
    @DisplayName("a run builds the drained projects after a gated one: the gated project starts no run and does not "
            + "stop the run; a start records an attempt left running for longer than a lock's lease")
    void runsPastAGatedProject() {
        var gated = UUID.randomUUID();
        project(WORKSPACE_ID, gated, 5000);
        project(WORKSPACE_ID, started, 140);
        backfills.add(backfill(WORKSPACE_ID, gated).build());
        var before = Instant.now();

        service.run(START);

        var after = Instant.now();
        var lease = runner.projectLease();
        verify(rebuildService).startRun(eq(WORKSPACE_ID), eq(started), eq(TopicProject.RunMode.FIRST_BUILD),
                argThat(abandonedBefore -> !abandonedBefore.isBefore(before.minus(lease))
                        && !abandonedBefore.isAfter(after.minus(lease))));
        verify(rebuildService, never()).startRun(any(), eq(gated), any(), any());
    }

    @Test
    @DisplayName("a project whose lock another run holds, or that got a list since it was listed, is skipped, and "
            + "the run goes on")
    void skipsALockedOrBuiltProject() {
        var locked = UUID.randomUUID();
        var built = UUID.randomUUID();
        project(WORKSPACE_ID, locked, 900);
        project(WORKSPACE_ID, built, 800);
        project(WORKSPACE_ID, started, 140);
        doReturn(Mono.just(false)).when(lockService).lockUsingToken(eq(TopicsProjectRunner.projectLock(locked)),
                any());
        // The list is saved after the candidates are read, before the project's turn.
        doAnswer(invocation -> {
            withList.add(built);
            return Mono.just(true);
        }).when(lockService).lockUsingToken(eq(TopicsProjectRunner.projectLock(built)), any());

        service.run(START);

        verify(rebuildService).startRun(eq(WORKSPACE_ID), eq(started), eq(TopicProject.RunMode.FIRST_BUILD), any());
        verify(rebuildService, never()).startRun(any(), eq(locked), any(), any());
        verify(rebuildService, never()).startRun(any(), eq(built), any(), any());
        verify(lockService, never()).unlockUsingToken(TopicsProjectRunner.projectLock(locked));
        verify(lockService).unlockUsingToken(TopicsProjectRunner.projectLock(built));
    }

    @Test
    @DisplayName("the unfinished labelling passes of the eligible workspaces go on before the builds, the oldest save "
            + "first, each under its project's lock and stopping by half the budget while a build waits; a project "
            + "whose lock another run holds is left for the next run")
    void resumesUnfinishedPasses() {
        var oldest = UUID.randomUUID();
        var locked = UUID.randomUUID();
        var newer = UUID.randomUUID();
        unlabelled.add(new TopicDAO.ProjectKey(WORKSPACE_ID, oldest));
        unlabelled.add(new TopicDAO.ProjectKey(WORKSPACE_ID, locked));
        unlabelled.add(new TopicDAO.ProjectKey(WORKSPACE_ID, newer));
        // Paused or opted out: not eligible, so never read.
        unlabelled.add(new TopicDAO.ProjectKey(OTHER_WORKSPACE_ID, UUID.randomUUID()));
        project(WORKSPACE_ID, started, 140);
        doReturn(Mono.just(false)).when(lockService).lockUsingToken(eq(TopicsProjectRunner.projectLock(locked)),
                any());
        var halfBudget = config.getFirstBuild().getBudget().toJavaDuration().dividedBy(2);
        var before = Instant.now();

        service.run(START);

        var after = Instant.now();
        verify(topicDAO).findLabelsIncomplete(List.of(WORKSPACE_ID));
        var order = inOrder(labelService, rebuildService);
        for (var projectId : List.of(oldest, newer)) {
            order.verify(labelService).labelWindow(eq(WORKSPACE_ID), eq(projectId),
                    argThat(deadline -> deadline.isAfter(before.plus(halfBudget).minusSeconds(1))
                            && !deadline.isAfter(after.plus(halfBudget))),
                    eq(TopicWindowLabelService.Trigger.RESUME));
        }
        order.verify(rebuildService).startRun(eq(WORKSPACE_ID), eq(started), eq(TopicProject.RunMode.FIRST_BUILD),
                any());
        verify(labelService, never()).labelWindow(any(), eq(locked), any(), any());
        verify(labelService, times(2)).labelWindow(any(), any(), any(), any());
        verify(lockService).unlockUsingToken(TopicsProjectRunner.projectLock(oldest));
        verify(lockService).unlockUsingToken(TopicsProjectRunner.projectLock(newer));
        verify(lockService, never()).unlockUsingToken(TopicsProjectRunner.projectLock(locked));
    }

    @Test
    @DisplayName("while a build waits the passes get half the budget, and none when that half is shorter than a "
            + "page; with no build waiting they get all of it")
    void passesShareTheBudget() {
        var unfinished = UUID.randomUUID();
        unlabelled.add(new TopicDAO.ProjectKey(WORKSPACE_ID, unfinished));
        // Half of 15 s is under MIN_PAGE_TIME (10 s); all of it is not.
        config.getFirstBuild().setBudget(io.dropwizard.util.Duration.seconds(15));
        project(WORKSPACE_ID, started, 140);

        service.run(START);

        verify(labelService, never()).labelWindow(any(), any(), any(), any());
        verify(rebuildService).startRun(eq(WORKSPACE_ID), eq(started), eq(TopicProject.RunMode.FIRST_BUILD), any());

        listless.clear();
        var before = Instant.now();

        service.run(START);

        verify(labelService).labelWindow(eq(WORKSPACE_ID), eq(unfinished),
                argThat(deadline -> !deadline.isBefore(before.plusSeconds(15))
                        && !deadline.isAfter(Instant.now().plusSeconds(15))),
                eq(TopicWindowLabelService.Trigger.RESUME));
    }

    @Test
    @DisplayName("a run that cannot start is counted once, as start_failed, and the next project is still built")
    void startFailure() {
        project(WORKSPACE_ID, started, 140);
        project(WORKSPACE_ID, next, 120);
        doThrow(new IllegalStateException("MySQL down")).when(rebuildService).startRun(eq(WORKSPACE_ID), eq(started),
                eq(TopicProject.RunMode.FIRST_BUILD), any());

        service.run(START);

        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("start_failed");
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
        });
        verify(rebuildService).startRun(eq(WORKSPACE_ID), eq(next), eq(TopicProject.RunMode.FIRST_BUILD), any());
        verify(lockService).unlockUsingToken(TopicsProjectRunner.projectLock(started));
    }

    @Test
    @DisplayName("a build that throws fails its run with internal")
    void buildThatThrowsIsInternal() {
        var runId = UUID.randomUUID();
        project(WORKSPACE_ID, started, 140);
        doReturn(runId).when(rebuildService).startRun(eq(WORKSPACE_ID), eq(started),
                eq(TopicProject.RunMode.FIRST_BUILD), any());
        doThrow(new IllegalStateException("a bug in the build"))
                .when(rebuildService).firstBuild(eq(runId), anyString(), any(), any());
        when(rebuildService.fail(any(), anyString(), any(), any())).thenReturn(true);

        service.run(START);

        verify(rebuildService).fail(runId, WORKSPACE_ID, started, TopicProject.RunError.INTERNAL);
    }

    @Test
    @DisplayName("a run whose timeout comes after its save counts as saved, and keeps its lock until its insert ends")
    void timeoutAfterSaveIsSaved() {
        var runId = UUID.randomUUID();
        project(WORKSPACE_ID, started, 140);
        var events = Collections.synchronizedList(new ArrayList<String>());
        when(lockService.unlockUsingToken(TopicsProjectRunner.projectLock(started))).thenAnswer(invocation -> {
            events.add("unlock");
            return Mono.empty();
        });
        config.getBuild().setProjectTimeout(io.dropwizard.util.Duration.milliseconds(200));
        doReturn(runId).when(rebuildService).startRun(eq(WORKSPACE_ID), eq(started),
                eq(TopicProject.RunMode.FIRST_BUILD), any());
        // The commit is done; the trace_topics insert outlasts the timeout and goes on through the interrupt.
        doAnswer(invocation -> {
            rows.put(started, row(started).listRunId(runId).runId(runId).runStatus(TopicProject.RunStatus.SAVED)
                    .runStartedAt(START).build());
            Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(600));
            events.add("insert done");
            return TopicsRebuildService.Outcome.builder().runId(runId).status(TopicProject.RunStatus.SAVED).build();
        }).when(rebuildService).firstBuild(eq(runId), anyString(), any(), any());
        when(rebuildService.fail(any(), anyString(), any(), any())).thenReturn(false);

        service.run(Instant.now());

        verify(rebuildService).fail(runId, WORKSPACE_ID, started, TopicProject.RunError.TIMEOUT);
        assertThat(events).containsExactly("insert done", "unlock");
    }

    @Test
    @DisplayName("a run deletes the topics of the opted-in workspaces' deleted projects, reading the workspaces "
            + "once; a failed delete does not stop the run")
    void deletesDeletedProjectsData() {
        when(workspaceService.enabledWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID, OTHER_WORKSPACE_ID));
        when(topicDAO.deleteTopicsOfMissingProjects(any())).thenThrow(new IllegalStateException("MySQL down"));
        vectors.put(started, 140L);
        listless.add(new TopicDAO.ProjectKey(WORKSPACE_ID, started));

        service.run(START);

        verify(topicDAO).deleteTopicsOfMissingProjects(List.of(WORKSPACE_ID, OTHER_WORKSPACE_ID));
        verify(workspaceService).enabledWorkspaceIds();
        verify(rebuildService).firstBuild(any(), eq(WORKSPACE_ID), eq(started), any());
    }

    @Test
    @DisplayName("while another run on the pod holds the run slot past its wait, the job skips the project before its "
            + "lock and its attempt, and a run by hand answers 409; once the slot is free, the project is built and "
            + "the slot is given back")
    void runSlotHeld() {
        doReturn(Duration.ofMillis(50)).when(runner).runLimit();
        project(WORKSPACE_ID, started, 140);
        assertThat(runner.acquireRun(Duration.ZERO)).isTrue();
        try {
            service.run(START);
            assertThatThrownBy(() -> service.runNow(WORKSPACE_ID, started, NIGHT, false))
                    .isInstanceOf(ConflictException.class);
        } finally {
            runner.releaseRun();
        }
        verify(rebuildService, never()).startRun(any(), any(), any(), any());
        verify(lockService, never()).lockUsingToken(eq(TopicsProjectRunner.projectLock(started)), any());

        service.run(START);

        verify(rebuildService).firstBuild(any(), eq(WORKSPACE_ID), eq(started), any());
        assertThat(runner.acquireRun(Duration.ZERO)).isTrue();
    }

    @Test
    @DisplayName("a run by hand answers 503 with Retry-After when Redis fails, and 409 only when the lock is held")
    void byHandLockUnavailable() {
        vectors.put(started, 140L);
        doReturn(Mono.error(new IllegalStateException("Redis down"))).when(lockService)
                .lockUsingToken(eq(TopicsFirstBuildService.BY_HAND_LOCK), any());

        assertThatThrownBy(() -> service.runNow(WORKSPACE_ID, started, NIGHT, false))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, exception -> {
                    assertThat(exception.getResponse().getStatus()).isEqualTo(503);
                    assertThat(exception.getResponse().getHeaderString(HttpHeaders.RETRY_AFTER))
                            .isEqualTo(String.valueOf(TopicsProjectRunner.LOCK_RETRY_AFTER_SECONDS));
                });

        // The project's lock fails after the by-hand lock was taken: that one is released.
        doReturn(Mono.just(true)).when(lockService).lockUsingToken(eq(TopicsFirstBuildService.BY_HAND_LOCK), any());
        doReturn(Mono.error(new IllegalStateException("Redis down"))).when(lockService)
                .lockUsingToken(eq(TopicsProjectRunner.projectLock(started)), any());
        assertThatThrownBy(() -> service.runNow(WORKSPACE_ID, started, NIGHT, false))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(lockService).unlockUsingToken(TopicsFirstBuildService.BY_HAND_LOCK);

        doReturn(Mono.just(false)).when(lockService).lockUsingToken(eq(TopicsFirstBuildService.BY_HAND_LOCK), any());
        assertThatThrownBy(() -> service.runNow(WORKSPACE_ID, started, NIGHT, false))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a run by hand conflicts while the project's vectors backfill has not drained, releases its locks, "
            + "and starts with ignoreBackfill, a failed build's wait aside")
    void byHandWaitsForTheBackfillUnlessTold() {
        var runId = UUID.randomUUID();
        vectors.put(started, 140L);
        backfills.add(backfill(WORKSPACE_ID, started).build());
        rows.put(started, failedRow(started, 1, Instant.now(), Instant.now()));
        doReturn(runId).when(rebuildService).startRun(eq(WORKSPACE_ID), eq(started),
                eq(TopicProject.RunMode.FIRST_BUILD), any());

        assertThatThrownBy(() -> service.runNow(WORKSPACE_ID, started, NIGHT, false))
                .isInstanceOfSatisfying(ConflictException.class, exception -> assertThat(
                        ((ErrorMessage) exception.getResponse().getEntity()).getMessage())
                        .contains("vectors backfill", "gate 'waiting'", "ignore_backfill=true"));
        verify(rebuildService, never()).startRun(any(), any(), any(), any());
        verify(lockService).unlockUsingToken(TopicsProjectRunner.projectLock(started));
        verify(lockService).unlockUsingToken(TopicsFirstBuildService.BY_HAND_LOCK);

        assertThat(service.runNow(WORKSPACE_ID, started, NIGHT, true)).isEqualTo(runId);
    }

    /** A project with no list, a trace in the window and {@code count} vectors in it. */
    private void project(String workspaceId, UUID projectId, long count) {
        listless.add(new TopicDAO.ProjectKey(workspaceId, projectId));
        vectors.put(projectId, count);
    }

    /**
     * A project of the workspace with 500 vectors whose {@code count} failed first builds started from
     * {@code firstStartedAgo} before {@link #START}, the last one finished {@code lastFinishedAgo} before it.
     */
    private UUID failed(int count, Duration lastFinishedAgo, Duration firstStartedAgo) {
        var projectId = UUID.randomUUID();
        project(WORKSPACE_ID, projectId, 500);
        rows.put(projectId, failedRow(projectId, count, START.minus(firstStartedAgo), START.minus(lastFinishedAgo)));
        return projectId;
    }

    /** A project of the workspace with 500 vectors whose attempt started {@code startedAgo} before START, running. */
    private UUID running(Duration startedAgo) {
        var projectId = UUID.randomUUID();
        project(WORKSPACE_ID, projectId, 500);
        rows.put(projectId, row(projectId).runId(UUID.randomUUID()).runStatus(TopicProject.RunStatus.RUNNING)
                .runStartedAt(START.minus(startedAgo)).build());
        return projectId;
    }

    private TopicProject failedRow(UUID projectId, int count, Instant firstFailedAt, Instant lastFinishedAt) {
        return row(projectId).runId(UUID.randomUUID()).runStatus(TopicProject.RunStatus.FAILED)
                .runStartedAt(lastFinishedAt.minusSeconds(60)).runFinishedAt(lastFinishedAt).failedRuns(count)
                .firstFailedAt(firstFailedAt).build();
    }

    private static TopicProject.TopicProjectBuilder row(UUID projectId) {
        return TopicProject.builder().workspaceId(WORKSPACE_ID).projectId(projectId);
    }

    /** A backfill started now, not drained, nothing counted. */
    private static TopicVectorBackfillDAO.Backfill.BackfillBuilder backfill(String workspaceId, UUID projectId) {
        return TopicVectorBackfillDAO.Backfill.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .backfillStartedAt(Instant.now())
                .backfillLastDoneAt(Instant.now());
    }
}
