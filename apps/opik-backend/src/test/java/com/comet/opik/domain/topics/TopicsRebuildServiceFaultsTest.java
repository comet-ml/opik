package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.Project;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResult;
import com.comet.opik.infrastructure.inference.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import io.dropwizard.util.Duration;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.scheduler.VirtualTimeScheduler;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.net.SocketException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.comet.opik.domain.topics.TopicsMetricsReader.points;
import static com.comet.opik.domain.topics.TopicsMetricsReader.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rebuild service when a store fails or stalls on cue, which the containers of {@link TopicsRebuildServiceTest}
 * can't do: a failing window read, save or mark, the retried trace_topics insert, and the run and insert timeouts on
 * virtual time. The stores are mocked; the run builds the fixture's window, so its steps are the real ones. Also the
 * prototype check and the time bounds of the window read and the insert.
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
    private TopicRunStore store;
    private TopicsRebuildService service;
    /** The scheduler the entry point would hand in. */
    private final Scheduler compute = Schedulers.newBoundedElastic(2, 16, "topics-faults-compute");

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
        var config = TestConfigUtils.loadConfigTest().getTopics();
        store = new TopicRunStore(config, template, traceTopicsDAO, projectService, idGenerator, metrics);
        var reader = new TopicWindowReader(config, windowDAO);
        var llm = new TopicsLlm(config, client, metrics);
        service = new TopicsRebuildService(config, reader, llm, store, new TopicRunOutcomes(store), idGenerator,
                metrics, TopicsTestAlgorithm.algorithm());
    }

    @AfterEach
    void tearDown() {
        compute.dispose();
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
    @DisplayName("a run cancelled during its LLM call fails its attempt with timeout and cancels the call")
    void cancelDuringTheCall() {
        when(projectDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        var calls = new AtomicInteger();
        var cancelled = new AtomicInteger();
        when(client.generate(any(), any())).thenReturn(Mono.<GenerateResponse>never()
                .doOnSubscribe(__ -> calls.incrementAndGet())
                .doOnCancel(cancelled::incrementAndGet));

        var subscription = service.firstBuild(runId, workspace, projectId, night, TRACES, compute).subscribe();
        Awaitility.await().until(() -> calls.get() == 1);
        subscription.dispose();

        Awaitility.await().untilAsserted(() -> verify(projectDAO).markFailed(eq(workspaceId), eq(projectId),
                eq(runId), any(), eq(TopicProject.RunError.TIMEOUT)));
        assertThat(cancelled).hasValue(1);
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "timeout", "workspace_name",
                workspaceName)).isEqualTo(1);
        verify(topicDAO, never()).insertTopics(any());
    }

    @Test
    @DisplayName("a MySQL save that fails writes no trace_topics row and counts the run failed once")
    void failedSaveWritesNoLabels() {
        when(projectDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        when(projectDAO.saveList(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("MySQL down"));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, workspace, projectId, night, TRACES, compute).block();

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

        var outcome = service.firstBuild(runId, workspace, projectId, night, TRACES, compute).block();

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

        var outcome = service.firstBuild(runId, workspace, projectId, night, TRACES, compute).block();

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

        var outcome = service.firstBuild(runId, workspace, projectId, night, TRACES, compute).block();

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
        var scheduler = VirtualTimeScheduler.create();
        TopicRunOutcome outcome;
        try {
            var run = service.firstBuild(runId, workspace, projectId, night, TRACES, scheduler).toFuture();
            // The run reaches the insert in real time; each step of virtual time then ends a stalled attempt.
            Awaitility.await().atMost(Duration.seconds(30).toJavaDuration()).until(() -> {
                scheduler.advanceTimeBy(READ_TIMEOUT.toJavaDuration());
                return run.isDone();
            });
            outcome = run.get();
        } finally {
            scheduler.dispose();
        }

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(1 + INSERT_RETRIES);
        assertThat(cancelled).as("every stalled attempt is cancelled")
                .hasValue(1 + INSERT_RETRIES);
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isZero();
    }

    @Test
    @DisplayName("a run that outlasts the project timeout before its commit fails with timeout and the window bounds, "
            + "cancels the LLM call in flight and saves nothing")
    void runTimeoutCancelsTheCallInFlight() throws Exception {
        when(projectDAO.markFailed(eq(workspaceId), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        var cancelled = new AtomicInteger();
        when(client.generate(any(), any()))
                .thenReturn(Mono.<GenerateResponse>never().doOnCancel(cancelled::incrementAndGet));
        var timeout = TestConfigUtils.loadConfigTest().getTopics().getBuild().getProjectTimeout().toJavaDuration();
        var scheduler = VirtualTimeScheduler.create();
        TopicRunOutcome outcome;
        try {
            var run = service.firstBuild(runId, workspace, projectId, night, TRACES, scheduler).toFuture();
            Awaitility.await().atMost(Duration.seconds(30).toJavaDuration()).until(() -> {
                scheduler.advanceTimeBy(timeout);
                return run.isDone();
            });
            outcome = run.get();
        } finally {
            scheduler.dispose();
        }

        assertThat(outcome.errorCode()).isEqualTo("timeout");
        assertThat(outcome.stats().has("window_from")).isTrue();
        assertThat(outcome.stats().has("window_traces")).as("a timed-out run carries the window bounds only")
                .isFalse();
        assertThat(points(metricReader, "topics.run.duration_seconds")).extracting(point -> point.get("phase"))
                .contains("window", "cluster", "llm");
        assertThat(cancelled).hasValue(1);
        verify(topicDAO, never()).insertTopics(any());
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "timeout")).isEqualTo(1);
    }

    /** A unit vector of {@code d} values: {@code a} at index {@code i}, {@code b} at index {@code j}. */
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
