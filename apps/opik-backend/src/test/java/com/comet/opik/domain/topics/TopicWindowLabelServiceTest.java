package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.WorkspaceNameService;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
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

import static com.comet.opik.domain.topics.TopicWindowLabelService.PassOutcome.COMPLETE;
import static com.comet.opik.domain.topics.TopicWindowLabelService.PassOutcome.CUT;
import static com.comet.opik.domain.topics.TopicWindowLabelService.PassOutcome.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The labelling pass with the stores mocked: MySQL holds a saved list, and ClickHouse is an in-memory window whose page
 * read leaves out the traces that have a row of the run, as {@code READ_UNLABELLED} does. Small pages and slices, so a
 * pass takes many of each.
 */
@DisplayName("topics labelling pass")
class TopicWindowLabelServiceTest {

    private static final int DIMENSIONS = TopicEmbedding.DIMENSIONS;
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
        var workspaceNames = mock(WorkspaceNameService.class);
        when(workspaceNames.nameOrId(WORKSPACE_ID)).thenReturn("Acme");
        config.getBuild().setWindowDays(WINDOW_DAYS);
        service = new TopicWindowLabelService(config, template, windowDAO, traceTopicsDAO,
                TopicsMetricsReader.metrics(InMemoryMetricReader.create()), workspaceNames,
                TopicsTestAlgorithm.algorithm());

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
        stubInsert();
    }

    @Test
    @DisplayName("every window trace gets exactly one row of the run, labelled by the saved prototypes; no trace "
            + "outside the window gets one, and the run's labels are marked complete")
    void labelsEveryWindowTraceOnce() {
        assertThat(label(Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(COMPLETE);

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
    }

    @Test
    @DisplayName("a saved list with no topic labels every window trace Other, as its run did")
    void noTopicLabelsOther() {
        when(topicDAO.findByProject(WORKSPACE_ID, projectId)).thenReturn(List.of());

        assertThat(label(Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(COMPLETE);

        assertExactlyOneRowPerWindowTrace();
        assertThat(inserted).extracting(TraceTopic::topicId).containsOnly(TraceTopic.OTHER);
        verify(topicDAO).markLabelsComplete(WORKSPACE_ID, projectId, runId);
    }

    @Test
    @DisplayName("traces that already have a row of the run (the run's own insert) are not labelled again")
    void skipsTheRunsOwnRows() {
        var window = inWindow(NIGHT.minus(Duration.ofDays(WINDOW_DAYS)), NIGHT);
        var sample = window.subList(0, 40).stream().map(this::ownRow).toList();
        inserted.addAll(sample);

        assertThat(label(Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(COMPLETE);

        assertExactlyOneRowPerWindowTrace();
        var own = sample.stream().map(TraceTopic::traceId).collect(Collectors.toSet());
        verify(traceTopicsDAO, never())
                .insert(argThat(rows -> rows.stream().anyMatch(row -> own.contains(row.traceId()))));
    }

    @Test
    @DisplayName("a failed insert fails the pass without erroring, touches no part of the saved list and marks "
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

        assertThat(label(Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(FAILED);

        assertThat(inserted).hasSize(2 * CHUNK);
        verify(topicDAO, never()).insertTopics(any());
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());

        assertThat(label(Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(COMPLETE);

        verify(topicDAO).markLabelsComplete(WORKSPACE_ID, projectId, runId);
        assertExactlyOneRowPerWindowTrace();
    }

    @Test
    @DisplayName("a pass past its deadline is cut before it reads anything, and marks nothing")
    void pastDeadlineIsCut() {
        assertThat(label(Instant.now().minusSeconds(1))).isEqualTo(CUT);

        verify(windowDAO, never()).countAndBounds(any(), any(), any(), any(), anyInt(), anyLong());
        verifyNoInteractions(traceTopicsDAO);
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());
    }

    @Test
    @DisplayName("a deadline that comes mid-pass cuts it: the pages inserted stay, the next is cancelled, and nothing "
            + "is marked complete")
    void deadlineCutsMidPass() {
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            List<TraceTopic> rows = invocation.getArgument(0);
            // The second page's insert outlasts the deadline.
            var delay = calls.incrementAndGet() == 2 ? Duration.ofSeconds(30) : Duration.ZERO;
            return Mono.delay(delay).then(Mono.fromRunnable(() -> inserted.addAll(rows)))
                    .thenReturn((long) rows.size());
        }).when(traceTopicsDAO).insert(any());

        assertThat(label(Instant.now().plusSeconds(2))).isEqualTo(CUT);

        assertThat(inserted).hasSize(CHUNK);
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());
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

        assertThat(label(Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(FAILED);

        int slices = (IN_WINDOW + SLICE_TRACES - 1) / SLICE_TRACES;
        assertThat(inserted).hasSize(slices * CHUNK);
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());
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

        assertThat(label(Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(FAILED);

        int slices = (IN_WINDOW + SLICE_TRACES - 1) / SLICE_TRACES;
        int maxPages = (SLICE_TRACES + CHUNK - 1) / CHUNK + 2;
        assertThat(inserted).hasSize(slices * maxPages * CHUNK);
        verify(topicDAO, never()).markLabelsComplete(any(), any(), any());
    }

    @Test
    @DisplayName("a project without a saved list has nothing to label")
    void noSavedList() {
        when(topicDAO.findProjects(eq(WORKSPACE_ID), any())).thenReturn(List.of());

        assertThat(label(Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(COMPLETE);

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

    /** Each insert adds its rows to {@code inserted}. */
    private void stubInsert() {
        doAnswer(invocation -> {
            List<TraceTopic> rows = invocation.getArgument(0);
            inserted.addAll(rows);
            return Mono.just((long) rows.size());
        }).when(traceTopicsDAO).insert(any());
    }

    private TopicWindowLabelService.PassOutcome label(Instant deadline) {
        return service.labelWindow(WORKSPACE_ID, projectId, deadline, TopicWindowLabelService.Trigger.RUN, CHUNK,
                SLICE_TRACES).block();
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
