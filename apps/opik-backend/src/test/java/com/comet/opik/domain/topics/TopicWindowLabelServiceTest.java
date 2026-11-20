package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The labelling pass with the stores mocked: MySQL holds a saved list, and ClickHouse is an in-memory window whose page
 * read leaves out the traces that have a row of the run, as {@code READ_UNLABELLED} does, and whose count per topic
 * counts the rows the pass wrote. Small pages and slices, so a pass takes many of each.
 */
@DisplayName("topics labelling pass")
class TopicWindowLabelServiceTest {

    private static final int DIMENSIONS = TopicsRebuildService.DIMENSIONS;
    private static final String WORKSPACE_ID = "ws-1";
    private static final Instant NIGHT = Instant.parse("2026-10-15T00:00:00Z");
    private static final int WINDOW_DAYS = 30;
    private static final int IN_WINDOW = 95;
    private static final int CHUNK = 7;
    private static final int SLICE_TRACES = 20;

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID billing = UUID.randomUUID();
    private final UUID refunds = UUID.randomUUID();
    private final Random random = new Random(11);

    private TopicDAO topicDAO;
    private TopicWindowDAO windowDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicWindowLabelService service;
    /** The window and the traces around it, by id. */
    private final Map<UUID, Trace> traces = new ConcurrentHashMap<>();
    /** Every row the pass inserted, in order. */
    private final List<TraceTopic> inserted = new CopyOnWriteArrayList<>();
    private float[] billingPrototype;
    private float[] refundsPrototype;
    private float[] otherPrototype;

    /** A trace, and the topic it is built to get: the one whose prototype it was nudged from, or Other. */
    private record Trace(UUID traceId, Instant createdAt, float[] vector, UUID expectedTopic) {
    }

    @BeforeEach
    void setUp() {
        topicDAO = mock(TopicDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        windowDAO = mock(TopicWindowDAO.class);
        traceTopicsDAO = mock(TraceTopicsDAO.class);
        var config = TestConfigUtils.loadConfigTest().getTopics();
        config.getBuild().setWindowDays(WINDOW_DAYS);
        service = new TopicWindowLabelService(config, template, windowDAO, traceTopicsDAO,
                new TopicsMetrics(new TopicsTestMeter().meter, workspaceId -> "Acme"), TopicsTestAlgorithm.algorithm());

        billingPrototype = unit(random);
        refundsPrototype = unit(random);
        otherPrototype = unit(random);
        stubProject(project -> project);
        when(topicDAO.markLabelsComplete(WORKSPACE_ID, projectId, runId)).thenReturn(1);
        // Billing has two prototypes, refunds one; the project keeps a known-Other one.
        when(topicDAO.findByProject(WORKSPACE_ID, projectId)).thenReturn(List.of(
                topic(billing, billingPrototype, unit(random)),
                topic(refunds, refundsPrototype)));

        var from = NIGHT.minus(Duration.ofDays(WINDOW_DAYS));
        var step = Duration.ofDays(WINDOW_DAYS).dividedBy(IN_WINDOW);
        var near = List.of(billingPrototype, refundsPrototype, otherPrototype);
        var nearTopics = List.of(billing, refunds, TraceTopic.OTHER);
        for (int i = 0; i < IN_WINDOW; i++) {
            // Every fourth trace is random noise, far from every prototype: Other by the threshold.
            var createdAt = from.plus(step.multipliedBy(i));
            if (i % 4 == 3) {
                add(createdAt, unit(random), TraceTopic.OTHER);
            } else {
                add(createdAt, nudge(near.get(i % 4), random), nearTopics.get(i % 4));
            }
        }
        // Outside the window: before it, and at its end.
        add(from.minusSeconds(1), nudge(billingPrototype, random), billing);
        add(NIGHT, nudge(billingPrototype, random), billing);

        when(windowDAO.countAndBounds(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    // As the query: every sliceTraces-th creation time of the window's traces, in order.
                    int sliceTraces = invocation.getArgument(4);
                    var created = inWindow(invocation.getArgument(2), invocation.getArgument(3)).stream()
                            .map(Trace::createdAt).sorted().toList();
                    var bounds = IntStream.rangeClosed(1, created.size()).filter(i -> i % sliceTraces == 0)
                            .mapToObj(i -> created.get(i - 1)).toList();
                    return Mono.just(new TopicWindowDAO.WindowBounds(created.size(), bounds));
                });
        when(windowDAO.readUnlabelled(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any(), any(), anyInt(),
                anyLong())).thenAnswer(invocation -> {
                    Instant pageFrom = invocation.getArgument(3);
                    Instant pageTo = invocation.getArgument(4);
                    String after = invocation.getArgument(5);
                    int limit = invocation.getArgument(6);
                    var done = inserted.stream().map(TraceTopic::traceId).collect(Collectors.toSet());
                    return Flux.fromIterable(inWindow(pageFrom, pageTo).stream()
                            .filter(trace -> trace.traceId().toString().compareTo(after) > 0)
                            .filter(trace -> !done.contains(trace.traceId()))
                            .limit(limit)
                            .map(trace -> new TopicWindowDAO.WindowVector(trace.traceId(), trace.createdAt(),
                                    trace.vector()))
                            .toList());
                });
        when(traceTopicsDAO.countByTopic(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyLong()))
                .thenAnswer(invocation -> Flux.fromIterable(countsOf(inserted).entrySet())
                        .map(entry -> new TraceTopicsDAO.TopicCount(entry.getKey(), entry.getValue())));
        stubInsert(false);
    }

    @Test
    @DisplayName("every window trace gets exactly one row of the run, labelled by the saved prototypes; no trace "
            + "outside the window gets one, and the run's labels are marked complete")
    void labelsEveryWindowTraceOnce() {
        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isTrue();
        assertThat(pass.runId()).isEqualTo(runId);
        assertThat(pass.windowTraces()).isEqualTo(IN_WINDOW);
        assertThat(pass.labelled()).isEqualTo(IN_WINDOW);
        var window = inWindow(NIGHT.minus(Duration.ofDays(WINDOW_DAYS)), NIGHT);
        assertThat(inserted).extracting(TraceTopic::traceId)
                .containsExactlyInAnyOrderElementsOf(window.stream().map(Trace::traceId).toList());
        assertThat(inserted).allSatisfy(row -> {
            assertThat(row.runId()).isEqualTo(runId);
            assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
            assertThat(row.traceCreatedAt()).isEqualTo(traces.get(row.traceId()).createdAt());
        });
        // Near a billing or refunds prototype: that topic; near the known-Other prototype or random: Other.
        var expected = new HashMap<UUID, UUID>();
        for (var trace : window) {
            expected.put(trace.traceId(), trace.expectedTopic());
        }
        assertThat(inserted.stream().collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId)))
                .isEqualTo(expected);
        assertThat(expected.values()).contains(billing, refunds, TraceTopic.OTHER);
        verify(topicDAO).markLabelsComplete(WORKSPACE_ID, projectId, runId);
        assertThat(pass.other()).isEqualTo(expected.values().stream().filter(TraceTopic.OTHER::equals).count());
    }

    @Test
    @DisplayName("a saved list with no topic labels every window trace Other, as its run did")
    void noTopicLabelsOther() {
        when(topicDAO.findByProject(WORKSPACE_ID, projectId)).thenReturn(List.of());

        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isTrue();
        assertExactlyOneRowPerWindowTrace();
        assertThat(inserted).extracting(TraceTopic::topicId).containsOnly(TraceTopic.OTHER);
        verify(topicDAO).markLabelsComplete(WORKSPACE_ID, projectId, runId);
        verify(traceTopicsDAO, never()).countByTopic(any(), any(), any(), any(), anyLong());
        assertThat(pass.counts()).isEmpty();
    }

    @Test
    @DisplayName("a complete pass logs the run's counts once the labels are marked complete: one entry per live topic "
            + "and Other, each the traces counted over the run's window, even when the run saved after its window's "
            + "end")
    void completePassLogsTheCounts() {
        stubProject(project -> project.lastSuccessAt(NIGHT.plus(Duration.ofHours(2))));

        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isTrue();
        verify(topicDAO).markLabelsComplete(WORKSPACE_ID, projectId, runId);
        assertThat(pass.counts()).isEqualTo(expectedCounts());
        verify(traceTopicsDAO).countByTopic(eq(WORKSPACE_ID), eq(projectId),
                eq(NIGHT.minus(Duration.ofDays(WINDOW_DAYS))), eq(NIGHT), anyLong());
    }

    @Test
    @DisplayName("a pass that is cut logs no counts; the pass that resumes it logs them once, at completion, and a "
            + "later complete pass of the same run logs nothing new")
    void countsLoggedOnceAtCompletion() {
        stubInsert(true);
        TopicWindowLabelService.Pass cut;
        try {
            cut = label(Instant.now().plus(Duration.ofMinutes(5)));
        } finally {
            Thread.interrupted();
        }
        assertThat(cut.complete()).isFalse();
        verify(traceTopicsDAO, never()).countByTopic(any(), any(), any(), any(), anyLong());
        assertThat(cut.counts()).isEmpty();

        stubInsert(false);
        var resumed = service.labelWindow(WORKSPACE_ID, projectId, Instant.now().plus(Duration.ofMinutes(5)),
                TopicWindowLabelService.Trigger.RESUME, CHUNK, SLICE_TRACES);

        assertThat(resumed.complete()).isTrue();
        assertThat(resumed.counts()).isEqualTo(expectedCounts());

        stubProject(project -> project.labelsComplete(true));
        var again = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(again.complete()).isTrue();
        verify(traceTopicsDAO, times(1)).countByTopic(any(), any(), any(), any(), anyLong());
        assertThat(again.counts()).isEmpty();
    }

    @Test
    @DisplayName("a failed count read still marks the labels complete, and logs no counts")
    void failedCountLogsNoCounts() {
        when(traceTopicsDAO.countByTopic(any(), any(), any(), any(), anyLong()))
                .thenReturn(Flux.error(new IllegalStateException("too many rows to read")));

        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isTrue();
        verify(topicDAO).markLabelsComplete(WORKSPACE_ID, projectId, runId);
        assertThat(pass.counts()).isEmpty();
    }

    @Test
    @DisplayName("an interrupt during the count cuts the pass: nothing is marked complete, no counts are logged, and "
            + "the interrupt is kept")
    void interruptedCountCutsThePass() {
        when(traceTopicsDAO.countByTopic(any(), any(), any(), any(), anyLong()))
                .thenReturn(Flux.error(new IllegalStateException(new InterruptedException("stop"))));

        TopicWindowLabelService.Pass pass;
        boolean interrupted;
        try {
            pass = label(Instant.now().plus(Duration.ofMinutes(5)));
        } finally {
            interrupted = Thread.interrupted();
        }

        assertThat(interrupted).isTrue();
        assertThat(pass.complete()).isFalse();
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());
        assertThat(pass.counts()).isEmpty();
    }

    @Test
    @DisplayName("a pass of a run that is no longer the project's saved run logs no counts")
    void olderRunLogsNoCounts() {
        when(topicDAO.markLabelsComplete(WORKSPACE_ID, projectId, runId)).thenReturn(0);

        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isTrue();
        assertThat(pass.counts()).isEmpty();
    }

    @Test
    @DisplayName("traces that already have a row of the run (the run's own insert) are not labelled again")
    void skipsTheRunsOwnRows() {
        var window = inWindow(NIGHT.minus(Duration.ofDays(WINDOW_DAYS)), NIGHT);
        var sample = window.subList(0, 40).stream().map(this::ownRow).toList();
        inserted.addAll(sample);

        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isTrue();
        assertThat(pass.labelled()).isEqualTo(IN_WINDOW - sample.size());
        assertExactlyOneRowPerWindowTrace();
    }

    @Test
    @DisplayName("a failed chunk ends the pass without throwing, touches no part of the saved list and marks "
            + "nothing complete; a later pass labels the rest, every window trace ends with exactly one row, and "
            + "only then are the labels marked complete")
    void failedChunkResumes() {
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            List<TraceTopic> rows = invocation.getArgument(0);
            // The third page fails on every attempt.
            if (calls.incrementAndGet() == 3) {
                return Mono.error(new IllegalStateException("ClickHouse down"));
            }
            return Mono.fromRunnable(() -> inserted.addAll(rows)).thenReturn((long) rows.size());
        }).when(traceTopicsDAO).insert(any());

        var cut = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(cut.complete()).isFalse();
        assertThat(cut.labelled()).isEqualTo(2L * CHUNK);
        assertThat(inserted).hasSize(2 * CHUNK);
        verify(topicDAO, never()).insertTopics(any());
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());

        var resumed = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(resumed.complete()).isTrue();
        assertThat(resumed.labelled()).isEqualTo(IN_WINDOW - 2L * CHUNK);
        verify(topicDAO).markLabelsComplete(WORKSPACE_ID, projectId, runId);
        assertExactlyOneRowPerWindowTrace();
    }

    @Test
    @DisplayName("a pass whose deadline is too close reads nothing, says it is not complete and marks nothing")
    void deadlineTooClose() {
        var pass = label(Instant.now().plus(TopicWindowLabelService.MIN_PAGE_TIME).minusSeconds(1));

        assertThat(pass.complete()).isFalse();
        assertThat(pass.labelled()).isZero();
        verify(windowDAO, never()).countAndBounds(any(), any(), any(), any(), anyInt(), anyLong());
        verifyNoInteractions(traceTopicsDAO);
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());
    }

    @Test
    @DisplayName("an interrupt stops the pass at the next page and the interrupt is kept")
    void interruptStops() {
        stubInsert(true);

        TopicWindowLabelService.Pass pass;
        boolean interrupted;
        try {
            pass = label(Instant.now().plus(Duration.ofMinutes(5)));
        } finally {
            interrupted = Thread.interrupted();
        }

        assertThat(interrupted).isTrue();
        assertThat(pass.complete()).isFalse();
        assertThat(inserted).hasSize(CHUNK);
    }

    @Test
    @DisplayName("a page that does not move past the last trace stops its slice; the other slices are labelled and "
            + "the pass ends incomplete")
    void stuckPageStopsTheSlice() {
        // A broken read: every page of a slice is its first page again.
        when(windowDAO.readUnlabelled(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any(), any(), anyInt(),
                anyLong())).thenAnswer(
                        invocation -> Flux.fromIterable(
                                inWindow(invocation.getArgument(3), invocation.getArgument(4)).stream()
                                        .limit(invocation.<Integer>getArgument(6))
                                        .map(trace -> new TopicWindowDAO.WindowVector(trace.traceId(),
                                                trace.createdAt(),
                                                trace.vector()))
                                        .toList()));

        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isFalse();
        int slices = (IN_WINDOW + SLICE_TRACES - 1) / SLICE_TRACES;
        assertThat(inserted).hasSize(slices * CHUNK);
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());
        assertThat(pass.counts()).isEmpty();
    }

    @Test
    @DisplayName("a slice stops after the pages it can hold, when every page is full; the pass ends incomplete")
    void pageCapStopsTheSlice() {
        // A broken read: every page is full of traces the slice never had.
        var next = new AtomicLong();
        when(windowDAO.readUnlabelled(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any(), any(), anyInt(),
                anyLong())).thenAnswer(
                        invocation -> Flux.range(0, invocation.<Integer>getArgument(6))
                                .map(i -> new TopicWindowDAO.WindowVector(
                                        new UUID(0x7000_0000_0000_7000L + (next.incrementAndGet() << 16),
                                                0x8000_0000_0000_0000L),
                                        invocation.getArgument(3), unit(random))));

        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isFalse();
        int slices = (IN_WINDOW + SLICE_TRACES - 1) / SLICE_TRACES;
        int maxPages = (SLICE_TRACES + CHUNK - 1) / CHUNK + 2;
        assertThat(inserted).hasSize(slices * maxPages * CHUNK);
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());
    }

    @Test
    @DisplayName("a project without a saved list has nothing to label")
    void noSavedList() {
        when(topicDAO.findProjects(eq(WORKSPACE_ID), any())).thenReturn(List.of());

        var pass = label(Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(pass.complete()).isTrue();
        assertThat(pass.runId()).isNull();
        verifyNoInteractions(windowDAO, traceTopicsDAO);
    }

    @Test
    @DisplayName("the slices cover the window exactly: each starts where the one before ends")
    void slicesCoverTheWindow() {
        var bounds = new CopyOnWriteArrayList<Instant[]>();
        when(windowDAO.readUnlabelled(any(), any(), any(), any(), any(), any(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    if ("".equals(invocation.getArgument(5))) {
                        bounds.add(new Instant[]{invocation.getArgument(3), invocation.getArgument(4)});
                    }
                    return Flux.empty();
                });

        label(Instant.now().plus(Duration.ofMinutes(5)));

        // 95 traces, 20 per slice: 5 slices.
        assertThat(bounds).hasSize(5);
        assertThat(bounds.getFirst()[0]).isEqualTo(NIGHT.minus(Duration.ofDays(WINDOW_DAYS)));
        assertThat(bounds.getLast()[1]).isEqualTo(NIGHT);
        for (int i = 1; i < bounds.size(); i++) {
            assertThat(bounds.get(i)[0]).isEqualTo(bounds.get(i - 1)[1]);
        }
    }

    /**
     * The project's saved list: run {@code runId}, its window ending at {@code NIGHT}, the known-Other prototype, plus
     * {@code extra}.
     */
    private void stubProject(UnaryOperator<TopicProject.TopicProjectBuilder> extra) {
        when(topicDAO.findProjects(eq(WORKSPACE_ID), any())).thenReturn(List.of(extra.apply(TopicProject.builder()
                .projectId(projectId)
                .workspaceId(WORKSPACE_ID)
                .savedRuns(1)
                .listRunId(runId)
                .windowUntil(NIGHT)
                .otherVectors(new PrototypeVectors(List.of(otherPrototype)))).build()));
    }

    /** Each insert adds its rows to {@code inserted}; with {@code interrupt}, it then interrupts the thread. */
    private void stubInsert(boolean interrupt) {
        doAnswer(invocation -> {
            List<TraceTopic> rows = invocation.getArgument(0);
            inserted.addAll(rows);
            if (interrupt) {
                Thread.currentThread().interrupt();
            }
            return Mono.just((long) rows.size());
        }).when(traceTopicsDAO).insert(any());
    }

    private TopicWindowLabelService.Pass label(Instant deadline) {
        return service.labelWindow(WORKSPACE_ID, projectId, deadline, TopicWindowLabelService.Trigger.RUN, CHUNK,
                SLICE_TRACES);
    }

    private void assertExactlyOneRowPerWindowTrace() {
        var window = inWindow(NIGHT.minus(Duration.ofDays(WINDOW_DAYS)), NIGHT);
        var rowsPerTrace = inserted.stream()
                .collect(Collectors.groupingBy(TraceTopic::traceId, Collectors.counting()));
        assertThat(rowsPerTrace.keySet())
                .isEqualTo(window.stream().map(Trace::traceId).collect(Collectors.toSet()));
        assertThat(rowsPerTrace.values()).containsOnly(1L);
    }

    private TraceTopic ownRow(Trace trace) {
        return TraceTopic.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .traceId(trace.traceId())
                .traceCreatedAt(trace.createdAt())
                .topicId(TraceTopic.OTHER)
                .source(TraceTopic.Source.RUN)
                .runId(runId)
                .build();
    }

    /** The traces created in {@code [from, to)}, in trace id order as the page read returns them. */
    private List<Trace> inWindow(Instant from, Instant to) {
        return traces.values().stream()
                .filter(trace -> !trace.createdAt().isBefore(from) && trace.createdAt().isBefore(to))
                .sorted(Comparator.comparing(trace -> trace.traceId().toString()))
                .toList();
    }

    private void add(Instant createdAt, float[] vector, UUID expectedTopic) {
        // A UUID v7 of the trace's time, with a random tail.
        long msb = (createdAt.toEpochMilli() << 16) | 0x7000L | (random.nextInt() & 0x0FFFL);
        long lsb = 0x8000000000000000L | (random.nextLong() & 0x3FFFFFFFFFFFFFFFL);
        var traceId = new UUID(msb, lsb);
        traces.put(traceId, new Trace(traceId, createdAt, vector, expectedTopic));
    }

    /** The window's traces per topic among {@code rows}, each trace by its last row, as the count read does. */
    private Map<UUID, Long> countsOf(List<TraceTopic> rows) {
        var window = inWindow(NIGHT.minus(Duration.ofDays(WINDOW_DAYS)), NIGHT).stream().map(Trace::traceId)
                .collect(Collectors.toSet());
        var latest = new HashMap<UUID, UUID>();
        rows.stream().filter(row -> window.contains(row.traceId()))
                .forEach(row -> latest.put(row.traceId(), row.topicId()));
        return latest.values().stream().collect(Collectors.groupingBy(topicId -> topicId, Collectors.counting()));
    }

    /** The counts a complete pass logs: billing, refunds and Other, as the traces were built to be labelled. */
    private Map<UUID, Long> expectedCounts() {
        var window = inWindow(NIGHT.minus(Duration.ofDays(WINDOW_DAYS)), NIGHT);
        return Stream.of(billing, refunds, TraceTopic.OTHER).collect(Collectors.toMap(topicId -> topicId,
                topicId -> window.stream().filter(trace -> trace.expectedTopic().equals(topicId)).count()));
    }

    private Topic topic(UUID topicId, float[]... vectors) {
        return Topic.builder()
                .id(topicId)
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .name("topic " + topicId)
                .definition("a topic")
                .born(LocalDate.ofInstant(NIGHT, ZoneOffset.UTC))
                .vectors(new PrototypeVectors(List.of(vectors)))
                .build();
    }

    private static float[] unit(Random random) {
        var vector = new float[DIMENSIONS];
        for (int t = 0; t < DIMENSIONS; t++) {
            vector[t] = (float) random.nextGaussian();
        }
        return normalize(vector);
    }

    /** A unit vector at cosine about 0.95 from {@code base}. */
    private static float[] nudge(float[] base, Random random) {
        var noise = unit(random);
        var vector = new float[DIMENSIONS];
        for (int t = 0; t < DIMENSIONS; t++) {
            vector[t] = base[t] + 0.3f * noise[t];
        }
        return normalize(vector);
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        for (int t = 0; t < vector.length; t++) {
            vector[t] /= (float) Math.sqrt(norm);
        }
        return vector;
    }
}
