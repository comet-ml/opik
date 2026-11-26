package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.Project;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The planner's plan over its night pass and discovery phase, with every store mocked: what one plan counts and
 * queues, and when it stops. The plans on real storage are in {@link TopicsRunsIntegrationTest} and
 * {@link TopicsNightlyIntegrationTest}.
 */
@DisplayName("topics run planner")
class TopicsRunPlannerTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 2);
    private static final Instant IN_BUDGET = Instant.parse("2026-11-02T02:10:00Z");
    private static final Instant NIGHT_START = NIGHT.atStartOfDay(ZoneOffset.UTC).toInstant();

    /** Rows with a saved list, for the night pass. */
    private final List<TopicProject> listed = new ArrayList<>();
    /** Projects with no list, for the discovery phase. */
    private final List<ListlessProject> listless = new ArrayList<>();
    private final List<String> workspaceIds = new ArrayList<>();
    private final List<PlannedRun> queued = new ArrayList<>();

    private TopicsConfig config;
    private TopicProjectDAO projectDAO;
    private TopicWindowDAO windowDAO;
    private TopicRunStore store;
    private TopicsRunPlanner planner;
    /** Each window count, by workspace and project; a count answers after {@link #countTakes}. */
    private Function<Collection<UUID>, Map<UUID, Long>> counts;
    private java.time.Duration countTakes = java.time.Duration.ZERO;

    @BeforeEach
    void setUp() {
        config = TestConfigUtils.loadConfigTest().getTopics();
        projectDAO = mock(TopicProjectDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicProjectDAO.class)).thenReturn(projectDAO);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        when(projectDAO.findNightDue(anyCollection(), any(), any())).thenAnswer(invocation -> listed.stream()
                .map(row -> new TopicProjectDAO.NightDue(row, false)).toList());
        when(projectDAO.findListless(anyCollection(), any())).thenAnswer(invocation -> List.copyOf(listless));
        when(projectDAO.findLabelsDue(anyCollection(), any())).thenReturn(List.of());

        var workspaceService = mock(TopicWorkspaceService.class);
        when(workspaceService.enabledWorkspaceIds(any())).thenAnswer(invocation -> List.copyOf(workspaceIds));
        var projectService = mock(ProjectService.class);
        when(projectService.findByIds(anyString(), any())).thenAnswer(invocation -> invocation
                .<Set<UUID>>getArgument(1).stream()
                .map(id -> Project.builder().id(id).name("project").build()).toList());
        var workspaceNames = mock(WorkspaceNameService.class);
        when(workspaceNames.nameOrId(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        counts = projectIds -> projectIds.stream().collect(Collectors.toMap(id -> id, id -> 500L));
        windowDAO = mock(TopicWindowDAO.class);
        when(windowDAO.countByProject(anyString(), anyCollection(), any(), any(), anyLong()))
                .thenAnswer(invocation -> Mono.delay(countTakes)
                        .map(__ -> counts.apply(invocation.<Collection<UUID>>getArgument(1))));
        var backfillGate = mock(TopicsBackfillGate.class);
        when(backfillGate.discoveryGate(any(), any())).thenReturn(TopicsBackfillGate.State.OPEN);
        var candidates = new TopicsDiscoveryCandidates(config, template, windowDAO, backfillGate);

        store = mock(TopicRunStore.class);
        when(store.findInFlight()).thenReturn(Mono.just(List.of()));
        when(store.queue(anyString(), any(), any())).thenAnswer(invocation -> Mono.just(ID_GENERATOR.generateId()));
        var reconciler = mock(TopicsRunReconciler.class);
        when(reconciler.reconcile(any(), any(), any(), any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        var publisher = mock(TopicsRunPublisher.class);
        when(publisher.publish(any())).thenAnswer(invocation -> {
            var message = invocation.<TopicsRunMessage>getArgument(0);
            queued.add(PlannedRun.builder().workspaceId(message.workspaceId()).projectId(message.projectId())
                    .mode(message.mode()).night(message.night()).windowTraces(message.windowTraces()).build());
            return Mono.empty();
        });

        var nightPass = new TopicsNightPass(config, template, workspaceService, candidates,
                TopicsMetricsReader.metrics(InMemoryMetricReader.create()), workspaceNames, projectService,
                mock(LockService.class));
        planner = new TopicsRunPlanner(config, template, workspaceService, store, reconciler, candidates, publisher,
                backfillGate, nightPass, mock(LockService.class));
    }

    private String workspace() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        workspaceIds.add(workspaceId);
        return workspaceId;
    }

    /** A project of the workspace with a list saved for the night before. */
    private UUID listed(String workspaceId) {
        return listed(workspaceId, 1);
    }

    /** A project of the workspace with a list saved {@code nightsAgo} nights before the night. */
    private UUID listed(String workspaceId, int nightsAgo) {
        var projectId = ID_GENERATOR.generateId();
        listed.add(TopicProject.builder().workspaceId(workspaceId).projectId(projectId)
                .listRunId(ID_GENERATOR.generateId()).windowUntil(NIGHT_START.minusSeconds(86_400L * nightsAgo))
                .build());
        return projectId;
    }

    /** A project of the workspace with no list, due for a discovery run. */
    private UUID listless(String workspaceId) {
        var projectId = ID_GENERATOR.generateId();
        listless.add(ListlessProject.builder().workspaceId(workspaceId).projectId(projectId).build());
        return projectId;
    }

    @Test
    @DisplayName("the night queues one workspace at a time in turn, stalest first within each: a workspace with "
            + "three stale projects does not take both free slots from one with a fresher project")
    void nightSharesTheSlotsAcrossWorkspaces() {
        var crowded = workspace();
        var stalest = listed(crowded, 5);
        listed(crowded, 4);
        listed(crowded, 3);
        var other = workspace();
        var fresher = listed(other, 1);

        assertThat(planner.plan(IN_BUDGET).block()).isEqualTo(2);

        assertThat(queued).extracting(PlannedRun::projectId).containsExactly(stalest, fresher);
        assertThat(queued).extracting(PlannedRun::mode).containsOnly(TopicProject.RunMode.UPDATE);
    }

    @Test
    @DisplayName("the night counts a workspace only when its turn comes: with 20 workspaces of 5 projects and 2 free "
            + "slots it counts 2 and queues 2, and records no decision on the projects it counted but did not queue")
    void nightCountsLazily() {
        for (int i = 0; i < 20; i++) {
            var workspaceId = workspace();
            for (int j = 0; j < 5; j++) {
                listed(workspaceId);
            }
        }

        assertThat(planner.plan(IN_BUDGET).block()).isEqualTo(2);

        verify(windowDAO, times(2)).countByProject(anyString(), anyCollection(), any(), any(), anyLong());
        assertThat(queued).hasSize(2).extracting(PlannedRun::workspaceId).doesNotHaveDuplicates();
        // The two queued projects only, one write each.
        verify(projectDAO, times(2)).markLooked(anyString(), anyCollection(), any());
    }

    @Test
    @DisplayName("the discovery phase counts a workspace only when its turn comes: with 20 workspaces of 5 projects "
            + "and 2 free slots it counts 2 and queues 2, one of each")
    void discoveryCountsLazily() {
        for (int i = 0; i < 20; i++) {
            var workspaceId = workspace();
            for (int j = 0; j < 5; j++) {
                listless(workspaceId);
            }
        }

        assertThat(planner.plan(IN_BUDGET).block()).isEqualTo(2);

        verify(windowDAO, times(2)).countByProject(anyString(), anyCollection(), any(), any(), anyLong());
        assertThat(queued).hasSize(2).extracting(PlannedRun::workspaceId).doesNotHaveDuplicates();
        assertThat(queued).extracting(PlannedRun::mode).containsOnly(TopicProject.RunMode.DISCOVERY);
    }

    @Test
    @DisplayName("the night writes a workspace's small and count-failed projects with its count, one write per "
            + "decision, not one per project")
    void nightRecordsSkipsOncePerWorkspace() {
        var workspaceId = workspace();
        var small = Set.of(listed(workspaceId), listed(workspaceId), listed(workspaceId));
        var failed = Set.of(listed(workspaceId), listed(workspaceId));
        counts = projectIds -> projectIds.stream().filter(small::contains)
                .collect(Collectors.toMap(id -> id, id -> 5L));

        assertThat(planner.plan(IN_BUDGET).block()).isZero();

        verify(projectDAO).markLooked(workspaceId, Set.copyOf(small), NIGHT);
        verify(projectDAO).markLooked(workspaceId, Set.copyOf(failed), NIGHT);
        verify(projectDAO, times(2)).markLooked(anyString(), anyCollection(), any());
    }

    @Test
    @DisplayName("one deadline covers the night pass and the discovery phase: with counts that take 30 s in both and "
            + "a 1 s plan budget, the plan cuts them and ends within 1.8 s, having queued nothing")
    void oneDeadlinePerPlan() {
        var workspaceId = workspace();
        listed(workspaceId);
        listless(workspaceId);
        countTakes = java.time.Duration.ofSeconds(30);
        config.getRuns().setPlanBudget(Duration.seconds(1));

        long started = System.nanoTime();
        assertThat(planner.plan(IN_BUDGET).block()).isZero();

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(
                java.time.Duration.ofMillis(1_800));
        assertThat(queued).isEmpty();
        verify(store, never()).queue(anyString(), any(), any());
    }
}
