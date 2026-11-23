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
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.net.SocketException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.domain.topics.TopicsMetricsReader.points;
import static com.comet.opik.domain.topics.TopicsMetricsReader.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private TopicProjectDAO projectDAO;
    private TopicDAO topicDAO;
    private ProjectService projectService;
    private TraceTopicsDAO traceTopicsDAO;
    private InferenceClient client;
    private TopicWindowDAO windowDAO;
    private WorkspaceNameService workspaceNames;
    private TopicRunStore store;
    private TopicsRebuildService service;
    private Function<TopicAlgorithm, TopicsRebuildService> serviceWith;

    @BeforeEach
    void setUp() throws Exception {
        metricReader = InMemoryMetricReader.create();
        projectDAO = mock(TopicProjectDAO.class);
        topicDAO = mock(TopicDAO.class);
        traceTopicsDAO = mock(TraceTopicsDAO.class);
        client = mock(InferenceClient.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicProjectDAO.class)).thenReturn(projectDAO);
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
        when(projectDAO.saveList(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(projectDAO.markSaved(any(), any(), any(), any())).thenReturn(1);
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.just((long) TRACES));
        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> IDS.generate());
        var metrics = TopicsMetricsReader.metrics(metricReader);
        workspaceNames = mock(WorkspaceNameService.class);
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
        when(projectDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any()))
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
    @DisplayName("a run cancelled while it looks up its workspace name fails its attempt with timeout, under the id")
    void cancelDuringNameLookup() throws Exception {
        when(projectDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        var lookupStarted = new CountDownLatch(1);
        var lookupReleased = new CountDownLatch(1);
        when(workspaceNames.nameOrId(workspaceId)).thenAnswer(invocation -> {
            lookupStarted.countDown();
            lookupReleased.await(5, TimeUnit.SECONDS);
            return workspaceName;
        });

        var subscription = service.firstBuild(runId, workspaceId, projectId, night, TRACES).subscribe();
        assertThat(lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        lookupReleased.countDown();

        Awaitility.await().untilAsserted(() -> verify(projectDAO).markFailed(eq(workspaceId), eq(projectId),
                eq(runId), any(), eq(TopicProject.RunError.TIMEOUT)));
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "timeout", "workspace_name",
                workspaceId)).isEqualTo(1);
        verify(windowDAO, never()).readWindow(any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("a window read that trips a ClickHouse guard fails the run with window_read, before the LLM call and "
            + "the save")
    void windowReadGuardHit() {
        when(projectDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        when(windowDAO.readWindow(eq(workspaceId), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.error(new IllegalStateException(
                        "Code: 158. DB::Exception: Limit for rows (controlled by 'max_rows_to_read' setting) exceeded")));

        var outcome = service.firstBuild(runId, workspaceId, projectId, night, TRACES).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("window_read");
        verify(projectDAO).markFailed(eq(workspaceId), eq(projectId), eq(runId), any(),
                eq(TopicProject.RunError.WINDOW_READ));
        verify(client, never()).generate(any(), any());
        verify(topicDAO, never()).insertTopics(any());
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "window_read")).isEqualTo(1);
    }

    @Test
    @DisplayName("a MySQL save that fails writes no trace_topics row and counts the run failed once")
    void failedSaveWritesNoLabels() {
        when(projectDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        when(projectDAO.saveList(any(), any(), any(), any(), any(), any(), any(), any()))
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
        var order = inOrder(projectDAO, traceTopicsDAO);
        order.verify(projectDAO).markSaved(eq(workspaceId), eq(projectId), eq(runId), any());
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
        verify(projectDAO, never()).markFailed(any(), any(), any(), any(), any());
        assertThat(points(metricReader, "topics.runs")).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("saved"));
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isZero();
        assertThat(sum(metricReader, "topics.save.rows", "table", "topics")).isPositive();
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "result", "error")).isEqualTo(1);
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
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "result", "error")).isEqualTo(1);
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "result", "success")).isZero();
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
    @DisplayName("a run that outlasts the project timeout before its commit fails with timeout, cancels the LLM call "
            + "in flight and saves nothing")
    void runTimeoutCancelsTheCallInFlight() throws Exception {
        when(projectDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
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
        verify(projectDAO).saveList(any(), any(), any(), any(), any(), any(), savedOther.capture(), any());
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
            public Optional<TopicLabeller> labeller(List<Topic> topics, PrototypeVectors otherVectors) {
                return Optional.empty();
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
        verify(projectDAO).saveList(eq(workspaceId), eq(projectId), isNull(), eq(runId), any(), any(),
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
