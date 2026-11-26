package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.Project;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsStage;
import com.comet.opik.infrastructure.lock.LockService;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.domain.topics.TopicsMetricsReader.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The night pass's faults that real containers cannot produce, with every store mocked: a failed count, a failed read,
 * a count that ends past the budget, a failed decision write, a failed night count. Also
 * the night the schedule gives. The decisions on real storage are in
 * {@link TopicsNightlyIntegrationTest}.
 */
@DisplayName("topics night pass")
class TopicsNightPassTest {

    private static final Duration PLAN_BUDGET = Duration.ofMinutes(2);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final String WORKSPACE_ID = ID_GENERATOR.generateId().toString();
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 2);
    private static final Instant START = Instant.parse("2026-11-02T02:00:00Z");
    private static final Instant NIGHT_START = NIGHT.atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant IN_BUDGET = START.plusSeconds(600);

    /** Each project's row; its window count in {@link #vectors}, or a failed count when absent. */
    private final Map<UUID, TopicProject> rows = new HashMap<>();
    private final Map<UUID, Long> vectors = new HashMap<>();
    private final List<PlannedRun> queued = new ArrayList<>();
    private Function<PlannedRun, Mono<UUID>> queue;

    private InMemoryMetricReader reader;
    private TopicsConfig config;
    private TopicProjectDAO projectDAO;
    private TopicWorkspaceService workspaceService;
    private TopicsDiscoveryCandidates candidates;
    private ProjectService projectService;
    private LockService lockService;
    private TopicsNightPass pass;

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        config = TestConfigUtils.loadConfigTest().getTopics();
        projectDAO = mock(TopicProjectDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicProjectDAO.class)).thenReturn(projectDAO);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));

        // As the query: a saved list, not looked at on the night, no run in flight; every project exists.
        when(projectDAO.findNightDue(anyCollection(), any(), any())).thenAnswer(invocation -> rows.values().stream()
                .filter(row -> row.listRunId() != null)
                .filter(row -> row.lastNight() == null || row.lastNight().isBefore(invocation.getArgument(1)))
                .filter(row -> row.runStatus() == null || !row.runStatus().inFlight())
                .map(row -> new TopicProjectDAO.NightDue(row, false))
                .toList());
        when(projectDAO.findProjects(eq(WORKSPACE_ID), any())).thenAnswer(invocation -> invocation
                .<Collection<UUID>>getArgument(1).stream().map(rows::get).toList());
        projectService = mock(ProjectService.class);
        when(projectService.findByIds(eq(WORKSPACE_ID), any())).thenAnswer(invocation -> invocation
                .<Set<UUID>>getArgument(1).stream()
                .map(id -> Project.builder().id(id).name("project").build()).toList());

        candidates = mock(TopicsDiscoveryCandidates.class);
        // As the count: a project with no count in vectors failed its own count and is left out.
        when(candidates.windowVectors(anyString(), anyCollection(), any())).thenAnswer(invocation -> Mono.just(
                invocation.<Collection<UUID>>getArgument(1).stream().filter(vectors::containsKey)
                        .collect(Collectors.toMap(id -> id, vectors::get))));
        workspaceService = mock(TopicWorkspaceService.class);
        when(workspaceService.enabledWorkspaceIds(TopicsStage.NIGHTLY)).thenReturn(List.of(WORKSPACE_ID));
        queue = planned -> {
            queued.add(planned);
            return Mono.just(ID_GENERATOR.generateId());
        };
        var workspaceNames = mock(WorkspaceNameService.class);
        when(workspaceNames.nameOrId(anyString()))
                .thenAnswer(invocation -> "name of " + invocation.<String>getArgument(0));
        lockService = mock(LockService.class);
        pass = new TopicsNightPass(config, template, workspaceService, candidates,
                TopicsMetricsReader.metrics(reader), workspaceNames, projectService, lockService);
    }

    /** A project with a list saved for the night before, and its window count. */
    private UUID listed(Long count) {
        var projectId = ID_GENERATOR.generateId();
        rows.put(projectId, row(projectId).build());
        if (count != null) {
            vectors.put(projectId, count);
        }
        return projectId;
    }

    private static TopicProject.TopicProjectBuilder row(UUID projectId) {
        return TopicProject.builder().workspaceId(WORKSPACE_ID).projectId(projectId)
                .listRunId(ID_GENERATOR.generateId())
                .windowUntil(NIGHT_START.minusSeconds(86_400));
    }

    private int plan(Instant now, int free) {
        return pass.plan(now, PlanDeadline.after(PLAN_BUDGET), free, queue).block();
    }

    private double decisions(String decision, String reason) {
        return reason == null
                ? sum(reader, "topics.nightly.decisions", "decision", decision)
                : sum(reader, "topics.nightly.decisions", "decision", decision, "reason", reason);
    }

    @Test
    @DisplayName("the night is the UTC date of the schedule's latest fire, today or yesterday, and starts at its first "
            + "fire that day; a schedule that fired on neither day gives none")
    void currentNight() {
        assertThat(TopicsNight.current("0 0 2 * * ?", Instant.parse("2026-11-02T03:00:00Z")))
                .contains(TopicsNight.builder().date(NIGHT).start(START).build());
        assertThat(TopicsNight.current("0 0 2 * * ?", Instant.parse("2026-11-02T01:00:00Z")))
                .contains(TopicsNight.builder().date(NIGHT.minusDays(1))
                        .start(START.minusSeconds(86_400)).build());
        assertThat(TopicsNight.current("0 0 * * * ?", Instant.parse("2026-11-02T05:30:00Z")))
                .contains(TopicsNight.builder().date(NIGHT).start(NIGHT_START).build());
        // 2026-11-02 is a Monday: a Friday-only schedule fired on neither day.
        assertThat(TopicsNight.current("0 0 2 ? * FRI", Instant.parse("2026-11-02T05:30:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("a project whose own window count fails is decided count_failed and not queued; the next night "
            + "counts it again")
    void countFails() {
        var broken = listed(null);

        assertThat(plan(IN_BUDGET, 2)).isZero();

        assertThat(queued).isEmpty();
        assertThat(decisions("count_failed", null)).isEqualTo(1);
        verify(projectDAO).markLooked(WORKSPACE_ID, Set.of(broken), NIGHT);
    }

    @Test
    @DisplayName("a pass whose read fails is counted as a failed night and queues nothing; with no workspace at stage "
            + "nightly nothing is read")
    void failedReadAndNoNightlyWorkspace() {
        when(projectDAO.findNightDue(anyCollection(), any(), any())).thenThrow(new IllegalStateException("MySQL down"));

        assertThat(plan(IN_BUDGET, 2)).isZero();
        assertThat(sum(reader, "topics.nightly.nights", "outcome", "failed")).isEqualTo(1);

        when(workspaceService.enabledWorkspaceIds(TopicsStage.NIGHTLY)).thenReturn(List.of());
        assertThat(plan(IN_BUDGET, 2)).isZero();
        verify(projectDAO).findNightDue(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("a window count that ends after the budget queues nothing and decides nothing: the night's end "
            + "records the project not reached")
    void countEndsAfterTheBudget() {
        listed(500L);
        when(candidates.windowVectors(anyString(), anyCollection(), any())).thenAnswer(invocation -> Mono
                .delay(Duration.ofMillis(300))
                .map(__ -> invocation.<Collection<UUID>>getArgument(1).stream()
                        .collect(Collectors.toMap(id -> id, vectors::get))));
        var budgetEnd = START.plus(config.getNightly().getBudget().toJavaDuration());

        assertThat(plan(budgetEnd.minusMillis(100), 2)).isZero();

        assertThat(queued).isEmpty();
        verify(projectDAO, never()).markLooked(anyString(), any(), any());
        assertThat(decisions("update", null)).isZero();
    }

    @Test
    @DisplayName("a decision whose write fails is not counted; the next plan decides the project again and counts it "
            + "once written")
    void failedWriteIsNotCounted() {
        var small = listed(5L);
        when(projectDAO.markLooked(WORKSPACE_ID, Set.of(small), NIGHT))
                .thenThrow(new IllegalStateException("MySQL down")).thenReturn(1);

        plan(IN_BUDGET, 2);
        assertThat(decisions("skip", "small")).isZero();

        plan(IN_BUDGET, 2);
        assertThat(decisions("skip", "small")).isEqualTo(1);
    }

    @Test
    @DisplayName("after the budget, a failed count of the night leaves its end key for the next plan, which ends the "
            + "night once")
    void failedNightCountKeepsTheEndKey() {
        var afterBudget = START.plus(config.getNightly().getBudget().toJavaDuration()).plusSeconds(60);
        when(projectDAO.findNotReached(anyCollection(), any(), any())).thenReturn(List.of());
        when(projectDAO.countNight(anyCollection(), any(), any())).thenThrow(new IllegalStateException("MySQL down"))
                .thenReturn(new TopicProjectDAO.NightCounts(1, 0));
        when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(true));

        plan(afterBudget, 2);
        verify(lockService, never()).lockUsingToken(any(), any());

        plan(afterBudget, 2);
        assertThat(sum(reader, "topics.nightly.nights", "outcome", "finished")).isEqualTo(1);
    }
}
