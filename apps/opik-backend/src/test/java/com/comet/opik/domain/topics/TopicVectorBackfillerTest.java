package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.domain.topics.TopicVectorWriter.ChunkWrite;
import com.comet.opik.domain.topics.TopicVectorsDAO.BackfillProject;
import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.domain.topics.TopicVectorsDAO.Slice;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.TopicsConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topic vectors backfiller")
class TopicVectorBackfillerTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final int MAX_EXCLUDED_PROJECTS = 20;
    private static final int MAX_DISCOVERIES_PER_TICK = 3;

    private final Instant now = TopicsTestClock.randomInstant();

    @Test
    @DisplayName("projects that drain in one read go first, smallest first, then the large ones, the most recently "
            + "active first among equals")
    void order() {
        int cap = 100;
        var small = backfill(10, now);
        var smallerOlder = backfill(5, now.minusSeconds(60));
        var largeRecent = backfill(500, now);
        var largeOlder = backfill(10_000, now.minusSeconds(60));
        var sameAsSmallOlder = backfill(10, now.minusSeconds(60));

        var ordered = List.of(largeOlder, small, sameAsSmallOlder, largeRecent, smallerOlder).stream()
                .sorted(TopicVectorBackfiller.order(cap)).toList();

        assertThat(ordered).containsExactly(smallerOlder, small, sameAsSmallOlder, largeRecent, largeOlder);
    }

    @Test
    @DisplayName("a discovery the budget cuts short between slices writes nothing; the next tick resumes it at the "
            + "next slice, gives each read the seconds left, and writes it once, as started by the first tick")
    void discoveryResumes() {
        var find = new TopicsConfig.FindConfig();
        find.setMaxExecutionTime(Duration.seconds(30));
        var vectors = new TopicsConfig.VectorsConfig();
        vectors.setFind(find);
        vectors.setEmbedTimeout(Duration.seconds(1));
        vectors.setLongWindowInterval(Duration.days(1));
        vectors.setMaxDiscoveriesPerTick(MAX_DISCOVERIES_PER_TICK);
        var config = new TopicsConfig();
        config.setVectors(vectors);
        var dao = mock(TopicVectorsDAO.class);
        var backfills = mock(TopicVectorBackfillService.class);
        var discovery = new TopicVectorDiscovery(config, dao, backfills, mock(TopicVectorsMetrics.class));
        var clock = new TopicsTestClock(now);
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var newer = new Slice(now.minus(7, ChronoUnit.DAYS), null);
        var older = new Slice(now.minus(14, ChronoUnit.DAYS), now.minus(7, ChronoUnit.DAYS));
        when(dao.slices(any())).thenReturn(List.of(newer, older));
        // Every read takes 6s of the tick.
        when(dao.findBackfillProjects(eq(workspaceId), any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            clock.advance(java.time.Duration.ofSeconds(6));
            return Flux.just(new BackfillProject(projectId, 2, now));
        });
        when(dao.countTracesWithVectorsByProject(eq(workspaceId), any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    clock.advance(java.time.Duration.ofSeconds(6));
                    return Mono.just(Map.of(projectId, 1L));
                });
        var lowerBound = now.minus(7, ChronoUnit.DAYS);
        // A read starts with half the budget (11s) plus 1s left and ends by the half: 22s fit two reads, of 11s and 5s.
        var tickBudget = java.time.Duration.ofSeconds(22);
        var first = TickBudget.of(now, tickBudget, clock);

        discovery.discover(List.of(workspaceId), lowerBound, older.from(), first).block();

        verify(dao).findBackfillProjects(workspaceId, TopicEmbedding.SUMMARY_TASK, lowerBound, newer, 11);
        verify(dao).findBackfillProjects(workspaceId, TopicEmbedding.SUMMARY_TASK, lowerBound, older, 5);
        verify(dao, never()).countTracesWithVectorsByProject(any(), any(), any(), any(), anyLong());
        verify(backfills, never()).discovered(any(), any(), any(), any());

        discovery.discover(List.of(workspaceId), lowerBound, older.from(),
                TickBudget.of(clock.instant(), tickBudget, clock)).block();

        verify(dao, times(2)).findBackfillProjects(any(), any(), any(), any(), anyLong());
        verify(dao, times(2)).countTracesWithVectorsByProject(any(), any(), any(), any(), anyLong());
        verify(backfills).discovered(workspaceId, List.of(TopicBackfill.builder().workspaceId(workspaceId)
                .projectId(projectId).backfillStartedAt(now).backfillLastDoneAt(now).backfillTracesTotal(4)
                .backfillTracesDone(2).build()), now, now.minus(2, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("discovery starts a slice only while half of the tick budget is left, so the fresh and backfill "
            + "phases keep the other half")
    void discoveryLeavesHalfTheBudget() {
        var setup = new Setup();
        setup.slices(4);
        setup.readsTake(java.time.Duration.ofSeconds(5));

        // 20s: a read starts with half (10s) plus 1s left, and is cut to end at the half: reads start at 20s and 15s
        // left, given 10s and 5s; at 10s left none starts.
        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(20)).block();

        verify(setup.dao).findBackfillProjects(any(), any(), any(), any(), eq(10L));
        verify(setup.dao).findBackfillProjects(any(), any(), any(), any(), eq(5L));
        verify(setup.dao, times(2)).findBackfillProjects(any(), any(), any(), any(), anyLong());
        verify(setup.backfills, never()).discovered(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a discovered workspace is not due again before longWindowInterval, and is due after it")
    void discoveryDue() {
        var setup = new Setup();
        setup.slices(1);

        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(50)).block();
        setup.clock.advance(java.time.Duration.ofHours(23));
        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(50)).block();

        verify(setup.backfills, times(1)).discovered(any(), any(), any(), any());

        setup.clock.advance(java.time.Duration.ofHours(1));
        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(50)).block();

        verify(setup.backfills, times(2)).discovered(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a workspace one pod discovered is not due on a new pod before longWindowInterval: the mark is the "
            + "store's, not the pod's")
    void discoveryDueAcrossPods() {
        var setup = new Setup();
        setup.slices(1);
        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(50)).block();
        setup.clock.advance(java.time.Duration.ofHours(23));

        new TopicVectorDiscovery(setup.config, setup.dao, setup.backfills, setup.metrics)
                .discover(List.of(setup.workspaceId), now, now, setup.budget(50)).block();

        verify(setup.backfills, times(1)).discovered(any(), any(), any(), any());
    }

    @Test
    @DisplayName("when the discovery marks cannot be read, every workspace is due, up to maxDiscoveriesPerTick, as on "
            + "a pod that never ran")
    void discoveryMarksUnreadable() {
        var setup = new Setup();
        setup.slices(1);
        var workspaces = IntStream.range(0, MAX_DISCOVERIES_PER_TICK + 1)
                .mapToObj(i -> ID_GENERATOR.generateId().toString()).toList();
        setup.discovery.discover(workspaces, now, now, setup.budget(50)).block();
        setup.discovery.discover(List.of(workspaces.getLast()), now, now, setup.budget(50)).block();
        verify(setup.backfills, times(workspaces.size())).discovered(any(), any(), any(), any());
        when(setup.backfills.findChecked(any())).thenThrow(new IllegalStateException("mysql"));

        setup.discovery.discover(workspaces, now, now, setup.budget(50)).block();

        verify(setup.backfills, times(workspaces.size() + MAX_DISCOVERIES_PER_TICK))
                .discovered(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a tick discovers at most maxDiscoveriesPerTick workspaces, a discovery in progress before a new "
            + "one")
    void discoveriesPerTick() {
        var setup = new Setup();
        setup.slices(2);
        var inProgress = ID_GENERATOR.generateId().toString();
        setup.readsTake(java.time.Duration.ofSeconds(5));
        // 11s: one of its four reads, then less than half is left.
        setup.discovery.discover(List.of(inProgress), now, now, setup.budget(11)).block();
        verify(setup.backfills, never()).discovered(any(), any(), any(), any());
        setup.readsTake(java.time.Duration.ZERO);
        var fresh = IntStream.range(0, MAX_DISCOVERIES_PER_TICK)
                .mapToObj(i -> ID_GENERATOR.generateId().toString()).toList();
        var workspaces = new ArrayList<>(fresh);
        workspaces.add(inProgress);

        setup.discovery.discover(workspaces, now, now, setup.budget(50)).block();

        var discovered = ArgumentCaptor.forClass(String.class);
        verify(setup.backfills, times(MAX_DISCOVERIES_PER_TICK))
                .discovered(discovered.capture(), any(), any(), any());
        assertThat(discovered.getAllValues()).first().isEqualTo(inProgress);
        assertThat(discovered.getAllValues()).doesNotContain(fresh.getLast());
    }

    @Test
    @DisplayName("a workspace no longer enabled loses its discovery in progress, which starts from the first slice "
            + "when it comes back; once discovered, it is due again only by its discovery mark")
    void disabledWorkspaceForgotten() {
        var setup = new Setup();
        var slices = setup.slices(2);
        setup.readsTake(java.time.Duration.ofSeconds(5));
        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(11)).block();
        setup.readsTake(java.time.Duration.ZERO);

        setup.discovery.discover(List.of(), now, now, setup.budget(50)).block();
        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(50)).block();

        // The first slice is read again: the discovery in progress was dropped.
        verify(setup.dao, times(2)).findBackfillProjects(eq(setup.workspaceId), any(), any(), eq(slices.getFirst()),
                anyLong());
        verify(setup.backfills).discovered(eq(setup.workspaceId), any(), any(), any());

        // Discovered, disabled and enabled again: due by its discovery mark, as any workspace.
        setup.discovery.discover(List.of(), now, now, setup.budget(50)).block();
        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(50)).block();
        verify(setup.backfills, times(1)).discovered(eq(setup.workspaceId), any(), any(), any());
        setup.clock.advance(java.time.Duration.ofDays(1));
        setup.discovery.discover(List.of(setup.workspaceId), now, now, setup.budget(50)).block();

        verify(setup.backfills, times(2)).discovered(eq(setup.workspaceId), any(), any(), any());
    }

    @Test
    @DisplayName("a workspace with more projects in backfill than maxExcludedProjects is not read; at the limit the "
            + "read leaves out the projects' ids again")
    void overExcludedProjects() {
        var setup = new Setup();
        var undrained = IntStream.range(0, MAX_EXCLUDED_PROJECTS + 1)
                .mapToObj(i -> backfill(1, now)).toList();

        assertThat(setup.backfiller.readableWithout(setup.workspaceId, undrained)).isEmpty();

        var atLimit = undrained.subList(0, MAX_EXCLUDED_PROJECTS);
        assertThat(setup.backfiller.readableWithout(setup.workspaceId, atLimit))
                .contains(atLimit.stream().map(TopicBackfill::projectId).toList());
    }

    @Test
    @DisplayName("a workspace's backfill takes the projects in order, one turn each; a project whose read stops at the "
            + "cap takes the next turn too; each turn gets the arrival steps the turn before left, and the progress of "
            + "every turn is recorded once, in turn order")
    void turnOrder() {
        var setup = new Setup();
        var small = backfill(1, now).toBuilder().workspaceId(setup.workspaceId).build();
        var smallest = backfill(2, now).toBuilder().workspaceId(setup.workspaceId).build();
        var largeRecent = backfill(100, now).toBuilder().workspaceId(setup.workspaceId).build();
        var largeOlder = backfill(100, now.minus(1, ChronoUnit.DAYS)).toBuilder().workspaceId(setup.workspaceId)
                .build();
        // Rows each read of a project returns: the large recent project's first read stops at the cap (10).
        var reads = Map.of(small.projectId(), new ArrayDeque<>(List.of(1)),
                smallest.projectId(), new ArrayDeque<>(List.of(2)),
                largeRecent.projectId(), new ArrayDeque<>(List.of(10, 3)),
                largeOlder.projectId(), new ArrayDeque<>(List.of(3)));
        when(setup.dao.findUnvectored(any())).thenAnswer(invocation -> {
            TopicVectorsFind find = invocation.getArgument(0);
            return Flux.fromStream(IntStream.range(0, reads.get(find.projectId()).poll())
                    .mapToObj(i -> candidate(setup.workspaceId, find.projectId())));
        });
        var turns = new AtomicInteger();
        when(setup.writer.embedAndWrite(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            List<Candidate> candidates = invocation.getArgument(1);
            return Mono.just(new ChunkWrite(TickResult.written(candidates.size()),
                    candidates.stream().map(VectorKey::of).collect(Collectors.toSet()),
                    ArrivalSteps.OFF.toBuilder().spent(java.time.Duration.ofSeconds(turns.incrementAndGet())).build()));
        });

        var result = setup.backfiller.backfill(TopicVectorsMetrics.Workspace.unnamed(setup.workspaceId),
                List.of(largeOlder, largeRecent, smallest, small), setup.window(10), setup.budget(50),
                ArrivalSteps.OFF).block();

        assertThat(result).isEqualTo(TickResult.written(19));
        var finds = ArgumentCaptor.forClass(TopicVectorsFind.class);
        verify(setup.dao, times(5)).findUnvectored(finds.capture());
        assertThat(finds.getAllValues()).isEqualTo(Stream.of(small, smallest, largeRecent, largeRecent, largeOlder)
                .map(project -> setup.window(10).toBuilder().projectId(project.projectId()).limit(10)
                        .maxExecutionSeconds(30).build())
                .toList());
        var arrivals = ArgumentCaptor.forClass(ArrivalSteps.class);
        verify(setup.writer, times(5)).embedAndWrite(any(), any(), any(), any(), arrivals.capture());
        assertThat(arrivals.getAllValues()).isEqualTo(IntStream.range(0, 5)
                .mapToObj(spent -> spent == 0
                        ? ArrivalSteps.OFF
                        : ArrivalSteps.OFF.toBuilder().spent(java.time.Duration.ofSeconds(spent)).build())
                .toList());
        verify(setup.backfills).record(List.of(
                progress(setup.workspaceId, small, now, 1),
                progress(setup.workspaceId, smallest, now, 2),
                progress(setup.workspaceId, largeRecent, null, 10),
                progress(setup.workspaceId, largeRecent, now, 3),
                progress(setup.workspaceId, largeOlder, now, 3)));
    }

    private static TopicBackfillProgress progress(String workspaceId, TopicBackfill project, Instant drainedAt,
            int written) {
        return TopicBackfillProgress.builder().workspaceId(workspaceId).projectId(project.projectId())
                .drainedAt(drainedAt).written(written).build();
    }

    @Test
    @DisplayName("a progress write that fails still returns the run's result, so the tick counts the vectors it wrote")
    void progressWriteFails() {
        var setup = new Setup();
        var project = backfill(2, now).toBuilder().workspaceId(setup.workspaceId).build();
        var candidates = IntStream.range(0, 2).mapToObj(i -> candidate(setup.workspaceId, project.projectId()))
                .toList();
        when(setup.dao.findUnvectored(any())).thenReturn(Flux.fromIterable(candidates));
        when(setup.writer.embedAndWrite(any(), any(), any(), any(), any())).thenReturn(Mono.just(new ChunkWrite(
                TickResult.written(2), candidates.stream().map(VectorKey::of).collect(Collectors.toSet()),
                ArrivalSteps.OFF)));
        doThrow(new IllegalStateException("mysql")).when(setup.backfills).record(any());

        var result = setup.backfiller.backfill(TopicVectorsMetrics.Workspace.unnamed(setup.workspaceId),
                List.of(project), setup.window(10), setup.budget(50), ArrivalSteps.OFF).block();

        assertThat(result.written()).isEqualTo(2);
        verify(setup.backfills).record(List.of(TopicBackfillProgress.builder().workspaceId(setup.workspaceId)
                .projectId(project.projectId()).drainedAt(now).written(2).build()));
    }

    @Test
    @DisplayName("on a pass tick, a workspace over maxExcludedProjects is not read by the workspace read, and does "
            + "not hold the long-window pass")
    void overExcludedWorkspaceOnAPassTick() {
        var setup = new Setup();
        var vectors = setup.config.getVectors();
        vectors.setTickInterval(Duration.minutes(1));
        vectors.setTickBudget(Duration.seconds(50));
        vectors.setWindow(Duration.days(1));
        vectors.setWatermarkOverlap(Duration.minutes(15));
        vectors.setLongWindow(Duration.days(7));
        vectors.setBackfillWindow(Duration.days(0));
        vectors.setEmbedConcurrency(1);
        vectors.getFind().setCreatedAtSlack(Duration.days(1));
        vectors.getFind().setSliceSize(Duration.days(1));
        var start = Instant.now();
        var undrained = IntStream.range(0, MAX_EXCLUDED_PROJECTS + 1)
                .mapToObj(i -> backfill(1, start).toBuilder().workspaceId(setup.workspaceId).build()).toList();
        when(setup.backfills.findUndrained(any())).thenReturn(Map.of(setup.workspaceId, undrained));
        when(setup.dao.findUnvectored(any())).thenReturn(Flux.empty());
        when(setup.writer.embedAndWrite(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChunkWrite(TickResult.EMPTY, Set.of(), ArrivalSteps.OFF)));
        var workspaces = mock(TopicWorkspaceService.class);
        when(workspaces.enabledWorkspaceIds()).thenReturn(List.of(setup.workspaceId));
        when(workspaces.vectorsWatermarks(any())).thenReturn(Map.of());
        when(workspaces.vectorsLongWindowReads(any())).thenReturn(Map.of());
        var registry = mock(AiLabelTaskRegistry.class);
        var task = mock(AiLabelTask.class);
        when(task.versions()).thenReturn(List.of(new AiLabelTask.Version(1, start.minus(30, ChronoUnit.DAYS))));
        when(registry.find(TopicEmbedding.SUMMARY_TASK)).thenReturn(Optional.of(task));
        var pass = mock(LongWindowPass.class);
        when(pass.startIfDue()).thenReturn(Mono.just(true));
        when(pass.finish(any(), anyBoolean())).thenReturn(Mono.empty());
        when(setup.metrics.workspace(any()))
                .thenAnswer(invocation -> Mono.just(TopicVectorsMetrics.Workspace.unnamed(invocation.getArgument(0))));
        var tick = new TopicVectorsTickService(setup.config, new AiLabelsConfig(), workspaces, setup.dao, registry,
                setup.writer, setup.discovery, setup.backfiller, pass, setup.metrics);

        tick.tick(start).block();

        verify(setup.dao, never()).findUnvectored(argThat(find -> find.projectId() == null));
        verify(pass).finish(start, true);
        verify(setup.metrics).workspaceNotRead(argThat(workspace -> workspace.id().equals(setup.workspaceId)),
                eq(TopicVectorsMetrics.NotRead.OVER_EXCLUDED));
    }

    /** A backfiller on mocks and a test clock, with a 30s read cap, a 1s embed timeout and a 1d interval. */
    private final class Setup {

        private final TopicVectorsDAO dao = mock(TopicVectorsDAO.class);
        private final TopicVectorBackfillService backfills = mock(TopicVectorBackfillService.class);
        private final TopicVectorWriter writer = mock(TopicVectorWriter.class);
        private final TopicVectorsMetrics metrics = mock(TopicVectorsMetrics.class);
        private final TopicsTestClock clock = new TopicsTestClock(now);
        private final String workspaceId = ID_GENERATOR.generateId().toString();
        private final TopicsConfig config = new TopicsConfig();
        private final TopicVectorBackfiller backfiller;
        private final TopicVectorDiscovery discovery;
        private java.time.Duration readTime = java.time.Duration.ZERO;
        /** The store's discovery marks, which every pod reads. */
        private final Map<String, Instant> checked = new ConcurrentHashMap<>();

        private Setup() {
            var find = new TopicsConfig.FindConfig();
            find.setMaxExecutionTime(Duration.seconds(30));
            var vectors = new TopicsConfig.VectorsConfig();
            vectors.setFind(find);
            vectors.setEmbedTimeout(Duration.seconds(1));
            vectors.setLongWindowInterval(Duration.days(1));
            vectors.setMaxTracesPerTick(10);
            vectors.setMaxExcludedProjects(MAX_EXCLUDED_PROJECTS);
            vectors.setMaxDiscoveriesPerTick(MAX_DISCOVERIES_PER_TICK);
            vectors.setOneSliceMaxTraces(250_000);
            config.setVectors(vectors);
            backfiller = new TopicVectorBackfiller(config, dao, backfills, writer, metrics);
            discovery = new TopicVectorDiscovery(config, dao, backfills, metrics);
            when(backfills.discovered(any(), any(), any(), any())).thenAnswer(invocation -> {
                checked.put(invocation.getArgument(0), invocation.getArgument(2));
                return 0;
            });
            when(backfills.findChecked(any())).thenAnswer(invocation -> Map.copyOf(checked));
            when(dao.findBackfillProjects(any(), any(), any(), any(), anyLong())).thenAnswer(invocation -> {
                clock.advance(readTime);
                return Flux.just(new BackfillProject(ID_GENERATOR.generateId(), 1, now));
            });
            when(dao.countTracesWithVectorsByProject(any(), any(), any(), any(), anyLong())).thenAnswer(invocation -> {
                clock.advance(readTime);
                return Mono.just(Map.of());
            });
        }

        private List<Slice> slices(int count) {
            var slices = IntStream.range(0, count)
                    .mapToObj(i -> new Slice(now.minus(i + 1, ChronoUnit.DAYS),
                            i == 0 ? null : now.minus(i, ChronoUnit.DAYS)))
                    .toList();
            when(dao.slices(any())).thenReturn(slices);
            return slices;
        }

        private void readsTake(java.time.Duration duration) {
            readTime = duration;
        }

        private TopicVectorsFind window(int limit) {
            return TopicVectorsFind.builder().workspaceId(workspaceId).task(TopicEmbedding.SUMMARY_TASK)
                    .lowerBound(now.minus(7, ChronoUnit.DAYS)).upperBound(now)
                    .createdAfter(now.minus(7, ChronoUnit.DAYS)).limit(limit).build();
        }

        private TickBudget budget(int seconds) {
            return TickBudget.of(clock.instant(), java.time.Duration.ofSeconds(seconds), clock);
        }
    }

    private Candidate candidate(String workspaceId, UUID projectId) {
        return Candidate.builder().workspaceId(workspaceId).projectId(projectId).traceId(ID_GENERATOR.generateId())
                .traceCreatedAt(now).taskVersion(1).statusUpdatedAt(now).summary("summary").build();
    }

    private static TopicBackfill backfill(long remaining, Instant lastDoneAt) {
        return TopicBackfill.builder()
                .workspaceId(ID_GENERATOR.generateId().toString())
                .projectId(ID_GENERATOR.generateId())
                .backfillStartedAt(lastDoneAt)
                .backfillLastDoneAt(lastDoneAt)
                .backfillTracesTotal(remaining)
                .build();
    }
}
