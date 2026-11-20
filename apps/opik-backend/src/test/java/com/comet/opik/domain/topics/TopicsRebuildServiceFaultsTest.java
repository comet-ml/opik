package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.Project;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.inference.GenerateRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResult;
import com.comet.opik.infrastructure.inference.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import io.dropwizard.util.Duration;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.net.SocketException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.domain.topics.TopicsMetricsReader.points;
import static com.comet.opik.domain.topics.TopicsMetricsReader.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rebuild service when a store fails or stalls on cue, which the containers of {@link TopicsRebuildServiceTest}
 * can't do: a failing window read, save or mark, the retried trace_topics insert, and the run and insert timeouts on
 * virtual time. The stores are mocked; the run builds the fixture's window, so its steps are the real ones. Also the
 * service's checks with a bound algorithm of the test's own, and the time bounds of the window read and the insert.
 */
@DisplayName("topics rebuild service faults")
class TopicsRebuildServiceFaultsTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int TRACES = 120;
    private static final TimeBasedEpochGenerator IDS = Generators.timeBasedEpochGenerator();
    private static final int INSERT_RETRIES = 3;
    private static final Duration READ_TIMEOUT = Duration.seconds(120);

    private final String workspaceId = IDS.generate().toString();
    private final String workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
    private final TopicsMetrics.Workspace workspace = new TopicsMetrics.Workspace(workspaceId, workspaceName);
    private final UUID projectId = IDS.generate();
    private final UUID runId = IDS.generate();
    private final LocalDate night = LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);

    private InMemoryMetricReader metricReader;
    private TopicProjectDAO runDAO;
    private TopicDAO topicDAO;
    private ProjectService projectService;
    private TraceTopicsDAO traceTopicsDAO;
    private InferenceClient client;
    private TopicWindowDAO windowDAO;
    private TopicRunStore store;
    private TopicsRebuildService service;
    private Function<TopicAlgorithm, TopicsRebuildService> serviceWith;

    @BeforeEach
    void setUp() throws Exception {
        metricReader = InMemoryMetricReader.create();
        runDAO = mock(TopicProjectDAO.class);
        topicDAO = mock(TopicDAO.class);
        traceTopicsDAO = mock(TraceTopicsDAO.class);
        client = mock(InferenceClient.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicProjectDAO.class)).thenReturn(runDAO);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        projectService = mock(ProjectService.class);
        when(projectService.findByIds(workspaceId, Set.of(projectId)))
                .thenReturn(List.of(Project.builder().id(projectId).name("p").build()));
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        windowDAO = mock(TopicWindowDAO.class);
        when(windowDAO.readWindow(eq(workspaceId), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.fromIterable(window()));
        when(runDAO.saveList(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(runDAO.markSaved(any(), any(), any(), any())).thenReturn(1);
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.just((long) TRACES));
        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> IDS.generate());
        var metrics = TopicsMetricsReader.metrics(metricReader);
        var workspaceNames = mock(WorkspaceNameService.class);
        when(workspaceNames.nameOrId(workspaceId)).thenReturn(workspaceName);
        var config = TestConfigUtils.loadConfigTest().getTopics();
        store = new TopicRunStore(config, template, traceTopicsDAO, projectService, idGenerator, metrics);
        var reader = new TopicWindowReader(config, windowDAO);
        var llm = new TopicsLlm(config, client, metrics);
        serviceWith = algorithm -> new TopicsRebuildService(config, reader, llm, store, idGenerator, metrics,
                workspaceNames, algorithm);
        service = serviceWith.apply(TopicsTestAlgorithm.algorithm());
    }

    @Test
    @DisplayName("a mark that throws counts nothing, as the run's row stays running; the mark that ends the row "
            + "counts it once, with its workspace")
    void markThatThrowsCountsNothing() {
        when(runDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any()))
                .thenThrow(new IllegalStateException("MySQL down"))
                .thenReturn(1);

        assertThatThrownBy(() -> store.fail(runId, workspace, projectId, TopicProject.RunError.LLM_ERROR).block())
                .isInstanceOf(IllegalStateException.class);
        assertThat(points(metricReader, "topics.runs")).isEmpty();

        assertThat(store.fail(runId, workspace, projectId, TopicProject.RunError.TIMEOUT).block()).isTrue();
        assertThat(points(metricReader, "topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("timeout");
            assertThat(point.get("workspace_id")).isEqualTo(workspaceId);
            assertThat(point.get("workspace_name")).isEqualTo(workspaceName);
        });
    }

    @Test
    @DisplayName("a window read that trips a ClickHouse guard fails the run with window_read, before Qwen and the save")
    void windowReadGuardHit() {
        when(runDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        when(windowDAO.readWindow(eq(workspaceId), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.error(new IllegalStateException(
                        "Code: 158. DB::Exception: Limit for rows (controlled by 'max_rows_to_read' setting) exceeded")));

        var outcome = service.firstBuild(runId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("window_read");
        verify(runDAO).markFailed(eq(workspaceId), eq(projectId), eq(runId), any(),
                eq(TopicProject.RunError.WINDOW_READ));
        verify(client, never()).generate(any(), any());
        verify(topicDAO, never()).insertTopics(any());
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "window_read")).isEqualTo(1);
    }

    @Test
    @DisplayName("a MySQL save that fails writes no trace_topics row and counts the run failed once")
    void failedSaveWritesNoLabels() {
        when(runDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        when(runDAO.saveList(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("MySQL down"));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.errorCode()).isEqualTo("save");
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(sum(metricReader, "topics.llm.calls", "outcome", "ok")).isEqualTo(1);
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "save")).isEqualTo(1);
        assertThat(sum(metricReader, "topics.runs", "status", "saved")).isZero();
        assertThat(points(metricReader, "topics.save.rows")).isEmpty();
    }

    @Test
    @DisplayName("the trace_topics insert comes after the MySQL commit, and a failed insert is retried")
    void insertAfterCommitIsRetried() {
        var attempts = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> attempts.incrementAndGet() <= 2
                ? Mono.error(new SocketException("Connection reset"))
                : Mono.just((long) TRACES)));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(3);
        var order = inOrder(runDAO, traceTopicsDAO);
        order.verify(runDAO).markSaved(eq(workspaceId), eq(projectId), eq(runId), any());
        order.verify(traceTopicsDAO).insert(any());
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isEqualTo(TRACES);
    }

    @Test
    @DisplayName("an insert whose transient error outlasts its 3 retries leaves the run saved, with no trace_topics rows "
            + "counted and the insert counted failed")
    void insertThatKeepsFailingLeavesTheRunSaved() {
        var attempts = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new SocketException("Connection reset"));
        }));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(1 + INSERT_RETRIES);
        verify(runDAO, never()).markFailed(any(), any(), any(), any(), any());
        assertThat(points(metricReader, "topics.runs")).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("saved"));
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isZero();
        assertThat(sum(metricReader, "topics.save.rows", "table", "topics")).isPositive();
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "outcome", "failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("an insert that fails with a permanent error is not retried and is counted failed")
    void permanentInsertErrorIsNotRetried() {
        var attempts = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalArgumentException("bad row"));
        }));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(1);
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "outcome", "failed")).isEqualTo(1);
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "outcome", "ok")).isZero();
    }

    @Test
    @DisplayName("a stalled insert attempt stops after 120 s, is cancelled and is retried; when every attempt stalls, "
            + "the run stays saved and its thread is freed")
    void stalledInsertStops() throws Exception {
        var attempts = new AtomicInteger();
        var cancelled = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.<Long>never().doOnCancel(cancelled::incrementAndGet);
        }));
        answer(FIXTURE.answer);
        var scheduler = VirtualTimeScheduler.getOrSet();
        TopicsRebuildService.Outcome outcome;
        try {
            var run = service.firstBuild(runId, workspaceId, projectId, night, TRACES).toFuture();
            // The run reaches the insert in real time; each step of virtual time then ends a stalled attempt.
            Awaitility.await().atMost(Duration.seconds(30).toJavaDuration()).until(() -> {
                scheduler.advanceTimeBy(READ_TIMEOUT.toJavaDuration());
                return run.isDone();
            });
            outcome = run.get();
        } finally {
            VirtualTimeScheduler.reset();
        }

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(1 + INSERT_RETRIES);
        assertThat(cancelled).as("every stalled attempt is cancelled")
                .hasValue(1 + INSERT_RETRIES);
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isZero();
    }

    @Test
    @DisplayName("a run that outlasts the project timeout before its commit fails with timeout, cancels the Qwen call "
            + "in flight and saves nothing")
    void runTimeoutCancelsTheCallInFlight() throws Exception {
        when(runDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        var cancelled = new AtomicInteger();
        when(client.generate(any(), any()))
                .thenReturn(Mono.<GenerateResponse>never().doOnCancel(cancelled::incrementAndGet));
        var timeout = TestConfigUtils.loadConfigTest().getTopics().getBuild().getProjectTimeout().toJavaDuration();
        var scheduler = VirtualTimeScheduler.getOrSet();
        TopicsRebuildService.Outcome outcome;
        try {
            var run = service.firstBuild(runId, workspaceId, projectId, night, TRACES).toFuture();
            Awaitility.await().atMost(Duration.seconds(30).toJavaDuration()).until(() -> {
                scheduler.advanceTimeBy(timeout);
                return run.isDone();
            });
            outcome = run.get();
        } finally {
            VirtualTimeScheduler.reset();
        }

        assertThat(outcome.errorCode()).isEqualTo("timeout");
        assertThat(cancelled).hasValue(1);
        verify(topicDAO, never()).insertTopics(any());
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "timeout")).isEqualTo(1);
    }

    /**
     * Four texts, three traces each, so each text is its own group and its vector is the group's centre. The answer
     * names the topic for the group of {@code alpha}: {@code beta} and {@code gamma} are Other groups within 0.95 of it
     * (gated), {@code delta} is an Other group far from it (kept).
     */
    @Test
    @DisplayName("the gated Other prototypes are not saved, and the saved rows assign as the build did")
    void gatedOtherIsNotSaved() throws Exception {
        int d = TopicEmbedding.DIMENSIONS;
        var alpha = unit(d, 0, 1, 0, 0);
        var gated = List.of(unit(d, 0, 0.97, 1, Math.sqrt(1 - 0.97 * 0.97)),
                unit(d, 0, 0.96, 2, Math.sqrt(1 - 0.96 * 0.96)));
        var delta = unit(d, 3, 1, 0, 0);
        var texts = List.of("alpha request", "beta request", "gamma request", "delta request");
        var vectors = List.of(alpha, gated.get(0), gated.get(1), delta);
        var rows = new ArrayList<TopicWindowDAO.WindowRow>();
        for (int i = 0; i < 12; i++) {
            rows.add(TopicWindowDAO.WindowRow.builder()
                    .traceId(IDS.generate())
                    .traceCreatedAt(FIXTURE.night.minusSeconds(3_600L * (i + 1)))
                    .taskVersion(1)
                    .text(texts.get(i % 4))
                    .vector(vectors.get(i % 4).clone())
                    .build());
        }
        when(windowDAO.readWindow(eq(workspaceId), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.fromIterable(rows));
        var cluster = Pattern.compile("cluster (\\d+): \\d+% of traffic\n    - alpha request");
        when(client.generate(any(), any())).thenAnswer(invocation -> {
            var prompt = invocation.<GenerateRequest>getArgument(0).messages().getFirst().content();
            var matcher = cluster.matcher(prompt);
            assertThat(matcher.find()).isTrue();
            var output = InferenceJson.MAPPER.readTree("{\"topics\": [{\"name\": \"Alpha Requests\", "
                    + "\"definition\": \"d\"}], \"assignment\": [{\"cluster\": " + matcher.group(1)
                    + ", \"topic\": 0}]}");
            return Mono.just(response(output));
        });

        var outcome = service.firstBuild(runId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(outcome.stats().get("other_groups").asInt()).isEqualTo(3);
        assertThat(outcome.stats().get("gated_other").asInt()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Topic>> savedTopics = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertTopics(savedTopics.capture());
        var savedOther = ArgumentCaptor.forClass(PrototypeVectors.class);
        verify(runDAO).saveList(any(), any(), any(), any(), any(), any(), savedOther.capture(), any());
        var topicRows = savedTopics.getValue().stream().flatMap(topic -> topic.vectors().vectors().stream())
                .toList();
        var otherRows = savedOther.getValue().vectors();
        assertThat(topicRows).hasSize(1);
        assertThat(otherRows).singleElement().satisfies(other -> assertThat(other).containsExactly(delta));
        for (var other : otherRows) {
            for (var topic : topicRows) {
                assertThat(dot(other, topic)).isLessThan(TopicsTestAlgorithm.config().getOtherGate());
            }
        }

        // The saved rows, with or without the gated twins, give the labels the build saved.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TraceTopic>> traceTopics = ArgumentCaptor.forClass(List.class);
        verify(traceTopicsDAO).insert(traceTopics.capture());
        var flat = new float[rows.size() * d];
        var savedLabels = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            System.arraycopy(row.vector(), 0, flat, i * d, d);
            var traceTopic = traceTopics.getValue().stream()
                    .filter(topicRow -> topicRow.traceId().equals(row.traceId())).findFirst().orElseThrow();
            savedLabels[i] = TraceTopic.OTHER.equals(traceTopic.topicId()) ? TopicAlgorithm.OTHER : 0;
        }
        var config = TopicsTestAlgorithm.config();
        var withoutGated = new PrototypeAssigner(topicRows, List.of(0), otherRows, d, config.getAssignMinCosine(),
                config.getOtherGate());
        var withGated = new PrototypeAssigner(topicRows, List.of(0),
                Stream.concat(otherRows.stream(), gated.stream()).toList(), d,
                config.getAssignMinCosine(), config.getOtherGate());
        assertThat(withoutGated.assign(flat)).containsExactly(savedLabels);
        assertThat(withGated.assign(flat)).containsExactly(savedLabels);
        assertThat(savedLabels).containsExactly(0, 0, 0, TopicAlgorithm.OTHER, 0, 0, 0, TopicAlgorithm.OTHER, 0,
                0, 0, TopicAlgorithm.OTHER);
    }

    @Test
    @DisplayName("the service saves what the bound algorithm returns: its topics with their prototypes, labels and "
            + "stats")
    void savesWhatTheAlgorithmReturns() throws Exception {
        int d = TopicEmbedding.DIMENSIONS;
        var first = unit(d, 0, 1, 1, 0);
        var second = unit(d, 1, 1, 0, 0);
        var other = unit(d, 2, 1, 0, 0);
        var stub = new TopicAlgorithm() {
            @Override
            public Clustering cluster(TopicWindow window, int maxTopics) {
                var stats = InferenceJson.MAPPER.createObjectNode().put("stub_stat", maxTopics);
                var author = LlmRequest.builder().call(LlmCall.AUTHOR).prompt("name these")
                        .schema(InferenceJson.MAPPER.createObjectNode()).maxTokens(10).build();
                return new Clustering() {
                    @Override
                    public LlmRequest author() {
                        return author;
                    }

                    @Override
                    public ObjectNode stats() {
                        return stats;
                    }

                    @Override
                    public FirstBuild finish(AuthorAnswer answer) {
                        var labels = new int[window.size()];
                        for (int i = 0; i < labels.length; i++) {
                            labels[i] = i % 3 == 2 ? OTHER : i % 3;
                        }
                        return FirstBuild.builder()
                                .topics(List.of(
                                        NewTopic.builder().name("First").definition("one")
                                                .prototypes(List.of(first)).build(),
                                        NewTopic.builder().name("Second").definition("two")
                                                .prototypes(List.of(second)).build()))
                                .labels(labels)
                                .otherPrototypes(List.of(other))
                                .stats(InferenceJson.MAPPER.createObjectNode())
                                .build();
                    }
                };
            }

            @Override
            public NightStep<TopicUpdate> update(NightState state, NightWindow window, int maxTopics) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TopicLabeller labeller(List<Topic> topics, PrototypeVectors otherVectors) {
                return vectors -> new UUID[vectors.size()];
            }
        };
        answer(FIXTURE.answer);

        var outcome = serviceWith.apply(stub).firstBuild(runId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Topic>> topics = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertTopics(topics.capture());
        var saved = topics.getValue();
        assertThat(saved).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id").containsExactly(
                topic("First", "one", first), topic("Second", "two", second));
        verify(runDAO).saveList(eq(workspaceId), eq(projectId), isNull(), eq(runId), any(), any(),
                eq(new PrototypeVectors(List.of(other))), eq(TopicEmbedding.MODEL));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TraceTopic>> traceTopics = ArgumentCaptor.forClass(List.class);
        verify(traceTopicsDAO).insert(traceTopics.capture());
        var topicIds = List.of(saved.get(0).id(), saved.get(1).id(), TraceTopic.OTHER);
        assertThat(traceTopics.getValue()).containsExactlyElementsOf(IntStream.range(0, TRACES)
                .mapToObj(i -> TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(FIXTURE.traceIds.get(i))
                        .traceCreatedAt(FIXTURE.createdAt.get(i))
                        .topicId(topicIds.get(i % 3))
                        .source(TraceTopic.Source.RUN)
                        .runId(runId)
                        .build())
                .toList());
        var json = outcome.stats();
        assertThat(json.get("stub_stat").asInt()).isEqualTo(10);
        assertThat(json.get("topics").asInt()).isEqualTo(2);
        assertThat(json.get("other_share").asDouble()).isEqualTo(1.0 / 3);
    }

    /** A topic of a third of the window, born on the run's night, with one prototype. */
    private Topic topic(String name, String definition, float[] prototype) {
        return Topic.builder()
                .id(TraceTopic.OTHER)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name(name)
                .definition(definition)
                .born(night)
                .size(TRACES / 3)
                .vectors(new PrototypeVectors(List.of(prototype)))
                .build();
    }

    /** A unit vector of {@code d} values: {@code a} at index {@code i}, {@code b} at index {@code j}. */
    private static float[] unit(int d, int i, double a, int j, double b) {
        var out = new float[d];
        out[i] = (float) a;
        out[j] += (float) b;
        return out;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int t = 0; t < a.length; t++) {
            s += (double) a[t] * b[t];
        }
        return s;
    }

    @Test
    @DisplayName("a prototype passes the check before the save only with 384 values and unit length, NaN fails")
    void unitPrototype() {
        int d = TopicEmbedding.DIMENSIONS;
        // The embedding model's output size, independent of the constant the code reads.
        assertThat(d).isEqualTo(384);
        var unit = unit(d, 0, 0.6, 1, 0.8);
        assertThat(PrototypeVectors.isUnit(unit)).isTrue();
        var nearUnit = unit.clone();
        nearUnit[0] *= 1.0005f;
        assertThat(PrototypeVectors.isUnit(nearUnit)).isTrue();

        var tooLong = unit.clone();
        tooLong[0] = 0.7f;
        assertThat(PrototypeVectors.isUnit(tooLong)).isFalse();
        var nan = unit.clone();
        nan[2] = Float.NaN;
        assertThat(PrototypeVectors.isUnit(nan)).isFalse();
        var infinite = unit.clone();
        infinite[2] = Float.POSITIVE_INFINITY;
        assertThat(PrototypeVectors.isUnit(infinite)).isFalse();
        assertThat(PrototypeVectors.isUnit(new float[]{0.6f, 0.8f})).isFalse();
    }

    @Test
    @DisplayName("the window read stops after topics.build.readTimeout, or after a shorter run timeout; the insert's "
            + "bound covers every attempt and backoff")
    void readTimeoutAndInsertBound() {
        var build = TestConfigUtils.loadConfigTest().getTopics().getBuild();
        assertThat(TopicWindowReader.readTimeout(build)).isEqualTo(READ_TIMEOUT.toJavaDuration());
        assertThat(TopicRunStore.insertBound(build))
                .isEqualTo(Duration.milliseconds(4 * 120_000 + 3_500 + 30_000).toJavaDuration());

        build.setProjectTimeout(Duration.minutes(1));
        assertThat(TopicWindowReader.readTimeout(build)).isEqualTo(Duration.minutes(1).toJavaDuration());
    }

    @Test
    @DisplayName("a saved nightly update records the Other share it logged in its stats, and its live topics and churn, "
            + "with its workspace")
    void nightlyUpdateIsCounted() {
        firstBuildThenEmptyNaming();
        double firstBuildShare = sum(metricReader, "topics.run.other_share");

        var outcome = service.nightlyUpdate(IDS.generate(), workspaceId, projectId, night.plusDays(1), TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        var stats = outcome.stats();
        assertThat(stats.path("mode").asText()).isEqualTo("nightly_update");
        assertThat(stats.get("window_traces").asLong()).isEqualTo(TRACES);
        assertThat(stats.get("sample_traces").asLong()).isEqualTo(TRACES);
        // The first build's share, then the nightly update's: the value it logged, not computed again.
        assertThat(sum(metricReader, "topics.run.other_share"))
                .isCloseTo(firstBuildShare + stats.get("other_share").asDouble(), within(1e-9));
        assertThat(points(metricReader, "topics.topics.live")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(stats.get("topics").asDouble());
            assertThat(point.get("workspace_id")).isEqualTo(workspaceId);
            assertThat(point.get("workspace_name")).isEqualTo(workspaceName);
        });
        for (var name : List.of("topics.run.churn", "topics.run.churn_any")) {
            assertThat(points(metricReader, name)).as(name).singleElement().satisfies(point -> {
                assertThat(point.value()).isEqualTo(stats.get(name.substring("topics.run.".length())).asDouble());
                assertThat(point.get("workspace_id")).isEqualTo(workspaceId);
                assertThat(point.get("workspace_name")).isEqualTo(workspaceName);
            });
        }
        assertThat(sum(metricReader, "topics.runs", "status", "saved", "workspace_id", workspaceId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a nightly update saves the list by compare-and-set on the run it read, updates the kept topics and "
            + "writes its labels as a run's after its commit")
    @SuppressWarnings("unchecked")
    void nightlyUpdateSaves() {
        var built = firstBuildThenEmptyNaming();
        var updateRunId = IDS.generate();

        var outcome = service.nightlyUpdate(updateRunId, workspaceId, projectId, night.plusDays(1), TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        verify(runDAO).saveList(eq(workspaceId), eq(projectId), eq(runId), eq(updateRunId), any(),
                eq(night.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()), any(), eq(TopicEmbedding.MODEL));
        ArgumentCaptor<List<Topic>> updated = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).updateTopics(updated.capture());
        assertThat(updated.getValue()).extracting(Topic::id)
                .containsExactlyElementsOf(built.stream().map(Topic::id).toList());
        verify(topicDAO, never()).deleteTopics(any(), any(), any());
        verify(runDAO, never()).resetList(any(), any(), any(), any());
        ArgumentCaptor<List<TraceTopic>> rows = ArgumentCaptor.forClass(List.class);
        verify(traceTopicsDAO, times(2)).insert(rows.capture());
        assertThat(rows.getValue()).hasSize(TRACES)
                .allSatisfy(row -> {
                    assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
                    assertThat(row.runId()).isEqualTo(updateRunId);
                });
        var order = inOrder(runDAO, traceTopicsDAO);
        order.verify(runDAO).saveList(eq(workspaceId), eq(projectId), eq(runId), eq(updateRunId), any(), any(),
                any(), any());
        order.verify(runDAO).markSaved(eq(workspaceId), eq(projectId), eq(updateRunId), any());
        order.verify(traceTopicsDAO).insert(any());
    }

    @Test
    @DisplayName("a nightly update that leaves no live topic deletes every topic, clears the list by compare-and-set, "
            + "counts each retired topic, and writes no labels")
    void allRetiredClearsTheList() {
        var built = firstBuildThenEmptyNaming();
        // Each topic's only vector is far from every trace: no trace tonight, so it retires; naming births nothing.
        var far = new ArrayList<Topic>();
        for (int t = 0; t < built.size(); t++) {
            far.add(built.get(t).toBuilder().vectors(new PrototypeVectors(List.of(
                    TopicsTestData.unit(TopicEmbedding.DIMENSIONS - 1 - t)))).build());
        }
        when(topicDAO.findByProject(workspaceId, projectId)).thenReturn(far);
        when(runDAO.resetList(eq(workspaceId), eq(projectId), eq(runId), any())).thenReturn(1);
        var updateRunId = IDS.generate();

        var outcome = service.nightlyUpdate(updateRunId, workspaceId, projectId, night.plusDays(1), TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        var order = inOrder(topicDAO, runDAO);
        order.verify(runDAO).resetList(eq(workspaceId), eq(projectId), eq(runId), any());
        order.verify(topicDAO).deleteTopics(workspaceId, projectId, far.stream().map(Topic::id).toList());
        order.verify(runDAO).markSaved(eq(workspaceId), eq(projectId), eq(updateRunId), any());
        verify(runDAO, never()).saveList(any(), any(), eq(runId), any(), any(), any(), any(), any());
        verify(topicDAO, never()).updateTopics(any());
        assertThat(sum(metricReader, "topics.retired", "reason", "low_share", "workspace_id", workspaceId))
                .isEqualTo(far.size());
        // The first build's insert only.
        verify(traceTopicsDAO, times(1)).insert(any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"another run saved the project meanwhile", "MySQL is down"})
    @DisplayName("a nightly update whose MySQL save fails, as a conflict or with MySQL down, fails with save and "
            + "writes no trace_topics row: the traces keep last night's")
    void failedUpdateSaveWritesNoLabels(String failure) {
        firstBuildThenEmptyNaming();
        var save = when(runDAO.saveList(any(), any(), eq(runId), any(), any(), any(), any(), any()));
        if (failure.equals("MySQL is down")) {
            save.thenThrow(new IllegalStateException("MySQL down"));
        } else {
            save.thenReturn(0);
        }
        var updateRunId = IDS.generate();
        when(runDAO.markFailed(eq(workspaceId), eq(projectId), eq(updateRunId), any(), any())).thenReturn(1);

        var outcome = service.nightlyUpdate(updateRunId, workspaceId, projectId, night.plusDays(1), TRACES).block();

        assertThat(outcome.errorCode()).isEqualTo("save");
        verify(topicDAO, never()).updateTopics(any());
        verify(runDAO, never()).markSaved(eq(workspaceId), eq(projectId), eq(updateRunId), any());
        // The first build's insert only.
        verify(traceTopicsDAO, times(1)).insert(any());
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "save")).isEqualTo(1);
    }

    @Test
    @DisplayName("a nightly update's trace_topics insert comes after its MySQL commit, and a failed insert is retried")
    void updateInsertAfterCommitIsRetried() {
        firstBuildThenEmptyNaming();
        var attempts = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> attempts.incrementAndGet() <= 2
                ? Mono.error(new SocketException("Connection reset"))
                : Mono.just((long) TRACES)));
        var updateRunId = IDS.generate();

        var outcome = service.nightlyUpdate(updateRunId, workspaceId, projectId, night.plusDays(1), TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(3);
        var order = inOrder(runDAO, traceTopicsDAO);
        order.verify(runDAO).markSaved(eq(workspaceId), eq(projectId), eq(updateRunId), any());
        order.verify(traceTopicsDAO).insert(any());
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isEqualTo(2 * TRACES);
    }

    @Test
    @DisplayName("a nightly update whose trace_topics insert fails every attempt stays saved")
    void updateInsertExhausted() {
        firstBuildThenEmptyNaming();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.error(new SocketException("Connection reset")));
        var updateRunId = IDS.generate();

        var outcome = service.nightlyUpdate(updateRunId, workspaceId, projectId, night.plusDays(1), TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        verify(runDAO).markSaved(eq(workspaceId), eq(projectId), eq(updateRunId), any());
        // The first build's rows only.
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isEqualTo(TRACES);
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "outcome", "failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("a nightly update with no saved list fails with internal and writes nothing")
    void nightlyUpdateWithoutList() {
        var updateRunId = IDS.generate();
        when(runDAO.markFailed(eq(workspaceId), eq(projectId), eq(updateRunId), any(), any())).thenReturn(1);

        var outcome = service.nightlyUpdate(updateRunId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.errorCode()).isEqualTo("internal");
        verify(client, never()).generate(any(), any());
        verify(traceTopicsDAO, never()).insert(any());
    }

    @Test
    @DisplayName("born, retired and churn keep their own attributes next to the workspace's")
    void lifecycleMetricsHaveTheWorkspace() {
        var reader = InMemoryMetricReader.create();
        var metrics = TopicsMetricsReader.metrics(reader);

        metrics.topics(workspace, 7, 2);
        metrics.topics(workspace, 7, 0);
        metrics.retired(workspace, NightState.RetiredReason.LOW_SHARE);
        metrics.churn(workspace, new TopicChurn.Churn(0.04, 0.1));

        assertThat(points(reader, "topics.topics.born")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(2);
            assertThat(point.get("workspace_id")).isEqualTo(workspaceId);
            assertThat(point.get("workspace_name")).isEqualTo(workspaceName);
        });
        assertThat(points(reader, "topics.topics.live")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(14);
            assertThat(point.get("workspace_name")).isEqualTo(workspaceName);
        });
        assertThat(points(reader, "topics.retired")).singleElement().satisfies(point -> {
            assertThat(point.get("reason")).isEqualTo("low_share");
            assertThat(point.get("workspace_id")).isEqualTo(workspaceId);
            assertThat(point.get("workspace_name")).isEqualTo(workspaceName);
        });
        assertThat(points(reader, "topics.run.churn")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(0.04);
            assertThat(point.get("workspace_id")).isEqualTo(workspaceId);
        });
        assertThat(points(reader, "topics.run.churn_any")).singleElement()
                .satisfies(point -> assertThat(point.value()).isEqualTo(0.1));
    }

    /**
     * The topics and the project row the first build saved, as the nightly update reads them back. Returns the
     * topics.
     */
    @SuppressWarnings("unchecked")
    private List<Topic> readBackFirstBuild() {
        ArgumentCaptor<List<Topic>> topics = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertTopics(topics.capture());
        var otherVectors = ArgumentCaptor.forClass(PrototypeVectors.class);
        var windowUntil = ArgumentCaptor.forClass(Instant.class);
        verify(runDAO).saveList(eq(workspaceId), eq(projectId), isNull(), eq(runId), any(),
                windowUntil.capture(), otherVectors.capture(), eq(TopicEmbedding.MODEL));
        verify(traceTopicsDAO).insert(any());
        var project = TopicProject.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .savedRuns(1)
                .listRunId(runId)
                .windowUntil(windowUntil.getValue())
                .otherVectors(otherVectors.getValue())
                .embedModel(TopicEmbedding.MODEL)
                .build();
        when(runDAO.findProjects(eq(workspaceId), any())).thenReturn(List.of(project));
        when(topicDAO.findByProject(workspaceId, projectId)).thenReturn(topics.getValue());
        return topics.getValue();
    }

    /**
     * The first build, read back as the nightly update reads it, then a naming answer that changes nothing: most nights
     * make no LLM call, and any that comes changes nothing. Returns the first build's topics.
     */
    private List<Topic> firstBuildThenEmptyNaming() {
        answer(FIXTURE.answer);
        service.firstBuild(runId, workspaceId, projectId, night, TRACES).block();
        var topics = readBackFirstBuild();
        answer(emptyNaming());
        return topics;
    }

    /** A naming answer that changes nothing. */
    private static JsonNode emptyNaming() {
        var naming = InferenceJson.MAPPER.createObjectNode();
        naming.putArray("assign");
        naming.putArray("new");
        naming.putArray("other");
        return naming;
    }

    private void answer(JsonNode output) {
        when(client.generate(any(), any())).thenReturn(Mono.just(response(output)));
    }

    private static GenerateResponse response(JsonNode output) {
        return GenerateResponse.builder().requestId("r-1").usage(new Usage(1_000, 200)).latencyMs(1_500)
                .result(new GenerateResult(output)).build();
    }

    private static List<TopicWindowDAO.WindowRow> window() {
        var rows = new ArrayList<TopicWindowDAO.WindowRow>(TRACES);
        for (int i = 0; i < TRACES; i++) {
            var vector = new float[TopicsFixture.DIMENSIONS];
            System.arraycopy(FIXTURE.vectors, i * TopicsFixture.DIMENSIONS, vector, 0, TopicsFixture.DIMENSIONS);
            rows.add(TopicWindowDAO.WindowRow.builder()
                    .traceId(FIXTURE.traceIds.get(i))
                    .traceCreatedAt(FIXTURE.createdAt.get(i))
                    .taskVersion(1)
                    .text(FIXTURE.intents[i])
                    .vector(vector)
                    .build());
        }
        return rows;
    }
}
