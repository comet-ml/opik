package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.inference.GenerateRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResult;
import com.comet.opik.infrastructure.inference.Usage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.util.Duration;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a run counts, with every store mocked: each failed run once, Qwen outcomes after the answer is read, and the
 * save order (MySQL first, then the retried trace_topics insert). A run builds the fixture's window, so its steps are
 * the real ones. Also the time bounds of the window read and of each insert attempt.
 */
@DisplayName("topics rebuild service metrics")
class TopicsRebuildServiceTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int TRACES = 120;
    private static final String WORKSPACE_ID = "ws-1";
    private static final String WORKSPACE_NAME = "Acme";

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final LocalDate night = LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);

    private TopicsTestMeter meter;
    private TopicRunDAO runDAO;
    private TopicDAO topicDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private InferenceClient client;
    private TopicWindowDAO windowDAO;
    private TopicWindowLabelService labelService;
    private TopicsRebuildService service;

    @BeforeEach
    void setUp() throws Exception {
        meter = new TopicsTestMeter();
        runDAO = mock(TopicRunDAO.class);
        topicDAO = mock(TopicDAO.class);
        traceTopicsDAO = mock(TraceTopicsDAO.class);
        client = mock(InferenceClient.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicRunDAO.class)).thenReturn(runDAO);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        windowDAO = mock(TopicWindowDAO.class);
        when(windowDAO.readWindow(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.fromIterable(window()));
        when(windowDAO.countByProject(eq(WORKSPACE_ID), eq(Set.of(projectId)), any(), any(), anyLong()))
                .thenReturn(Flux.just(new TopicWindowDAO.ProjectCount(projectId, TRACES)));
        labelService = mock(TopicWindowLabelService.class);
        when(labelService.labelWindow(any(), any(), any(), any()))
                .thenReturn(TopicWindowLabelService.Pass.builder().complete(true).build());
        when(topicDAO.findStates(any(), any())).thenReturn(List.of());
        when(topicDAO.lockProject(anyString(), any())).thenReturn(1);
        when(runDAO.markSaved(any(), any(), any(), any())).thenReturn(1);
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.just((long) TRACES));
        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
        var names = new TopicsWorkspaceNames(workspaceId -> WORKSPACE_NAME, Clock.systemUTC());
        service = new TopicsRebuildService(TestConfigUtils.loadConfigTest().getTopics(), template, windowDAO,
                traceTopicsDAO, client, idGenerator, new TopicsMetrics(meter.meter, names), labelService);
    }

    @Test
    @DisplayName("a failed mark counts the run once, with its workspace")
    void failCountsOnce() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);

        assertThat(service.fail(runId, WORKSPACE_ID, TopicsRunException.Code.TIMEOUT, "m", null)).isTrue();

        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("timeout");
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
    }

    @Test
    @DisplayName("a run that had already ended is not counted again")
    void alreadyEndedIsNotCounted() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(runId), any(), anyString(), anyString(), any())).thenReturn(0);

        assertThat(service.fail(runId, WORKSPACE_ID, TopicsRunException.Code.TIMEOUT, "m", null)).isFalse();

        assertThat(meter.points("topics.runs")).isEmpty();
    }

    @Test
    @DisplayName("a mark that throws counts nothing, as the run's row stays running; the mark that ends the row "
            + "counts it once")
    void markThatThrowsCountsNothing() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(runId), any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("MySQL down"))
                .thenReturn(1);

        assertThatThrownBy(() -> service.fail(runId, WORKSPACE_ID, TopicsRunException.Code.QWEN_ERROR, "m", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meter.points("topics.runs")).isEmpty();

        assertThat(service.fail(runId, WORKSPACE_ID, TopicsRunException.Code.TIMEOUT, "m", null)).isTrue();
        assertThat(meter.points("topics.runs")).singleElement()
                .satisfies(point -> assertThat(point.get("error_code")).isEqualTo("timeout"));
    }

    @Test
    @DisplayName("an answer that cannot be read counts bad_answer, not ok, and fails the run once")
    void unreadableAnswerIsBadAnswer() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);
        answer(InferenceJson.MAPPER.getNodeFactory().textNode("Sorry, I cannot name these clusters."));

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.errorCode()).isEqualTo("qwen_bad_answer");
        assertThat(meter.points("topics.qwen.calls")).singleElement().satisfies(point -> {
            assertThat(point.get("outcome")).isEqualTo("bad_answer");
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.sum("topics.runs", "status", "failed", "error_code", "qwen_bad_answer")).isEqualTo(1);
        assertThat(meter.points("topics.run.duration_seconds"))
                .allSatisfy(point -> assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID));
    }

    @Test
    @DisplayName("a window read that trips a ClickHouse guard fails the run with window_read, before Qwen and the save")
    void windowReadGuardHit() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);
        when(windowDAO.readWindow(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.error(new IllegalStateException(
                        "Code: 158. DB::Exception: Limit for rows (controlled by 'max_rows_to_read' setting) exceeded")));

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("window_read");
        verify(runDAO).markFailed(eq(WORKSPACE_ID), eq(runId), any(), eq("window_read"), anyString(), any());
        verify(client, never()).generate(any(), any());
        verify(topicDAO, never()).insertTopics(any());
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(meter.sum("topics.runs", "status", "failed", "error_code", "window_read")).isEqualTo(1);
    }

    @Test
    @DisplayName("a MySQL save that fails writes no trace_topics row and counts the run failed once")
    void failedSaveWritesNoLabels() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);
        when(topicDAO.lockProject(anyString(), any())).thenThrow(new IllegalStateException("MySQL down"));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.errorCode()).isEqualTo("save");
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(meter.sum("topics.qwen.calls", "outcome", "ok")).isEqualTo(1);
        assertThat(meter.sum("topics.runs", "status", "failed", "error_code", "save")).isEqualTo(1);
        assertThat(meter.sum("topics.runs", "status", "saved")).isZero();
        assertThat(meter.points("topics.save.rows")).isEmpty();
        verify(labelService, never()).labelWindow(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a second first save of the project fails as a conflict on the state's primary key and writes nothing")
    void secondSaveIsConflict() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);
        doThrow(new UnableToExecuteStatementException(new SQLIntegrityConstraintViolationException("duplicate"), null))
                .when(topicDAO).insertState(any());
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.errorCode()).isEqualTo("save");
        verify(runDAO).markFailed(eq(WORKSPACE_ID), eq(runId), any(), eq("save"),
                contains("another run saved this project meanwhile"), any());
        verify(topicDAO, never()).insertTopics(any());
        verify(topicDAO, never()).insertPrototypes(any());
        verify(runDAO, never()).markSaved(any(), any(), any(), any());
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(meter.sum("topics.runs", "status", "failed", "error_code", "save")).isEqualTo(1);
    }

    @Test
    @DisplayName("the trace_topics insert comes after the MySQL commit, and a failed insert is retried")
    void insertAfterCommitIsRetried() {
        var attempts = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> attempts.incrementAndGet() <= 2
                ? Mono.error(new IllegalStateException("ClickHouse down"))
                : Mono.just((long) TRACES)));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        assertThat(attempts).hasValue(3);
        var order = inOrder(runDAO, traceTopicsDAO);
        order.verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(runId), any(), any());
        order.verify(traceTopicsDAO).insert(any());
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics")).isEqualTo(TRACES);
    }

    @Test
    @DisplayName("an insert that fails after its 3 retries leaves the run saved, with no trace_topics rows counted")
    void insertThatKeepsFailingLeavesTheRunSaved() {
        var attempts = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("ClickHouse down"));
        }));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        assertThat(attempts).hasValue(1 + TopicsRebuildService.INSERT_RETRIES);
        verify(runDAO, never()).markFailed(any(), any(), any(), any(), any(), any());
        assertThat(meter.points("topics.runs")).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("saved"));
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics")).isZero();
        assertThat(meter.sum("topics.save.rows", "table", "topics")).isPositive();
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
            var run = CompletableFuture.supplyAsync(() -> service.firstBuild(runId, WORKSPACE_ID, projectId, night));
            // The run reaches the insert in real time; each step of virtual time then ends a stalled attempt.
            Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
                scheduler.advanceTimeBy(java.time.Duration.ofSeconds(TopicsRebuildService.READ_SECONDS));
                return run.isDone();
            });
            outcome = run.get();
        } finally {
            VirtualTimeScheduler.reset();
        }

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        assertThat(attempts).hasValue(1 + TopicsRebuildService.INSERT_RETRIES);
        assertThat(cancelled).as("every stalled attempt is cancelled")
                .hasValue(1 + TopicsRebuildService.INSERT_RETRIES);
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics")).isZero();
    }

    @Test
    @DisplayName("a timeout that interrupts the run after the commit does not stop the insert, and the interrupt is "
            + "kept")
    void insertGoesOnThroughAnInterrupt() {
        when(runDAO.markSaved(any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return 1;
        });
        when(traceTopicsDAO.insert(any()))
                .thenReturn(Mono.just((long) TRACES).delayElement(java.time.Duration.ofMillis(200)));
        answer(FIXTURE.answer);

        TopicsRebuildService.Outcome outcome;
        boolean interrupted;
        try {
            outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);
        } finally {
            interrupted = Thread.interrupted();
        }

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        assertThat(interrupted).isTrue();
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics")).isEqualTo(TRACES);
    }

    @Test
    @DisplayName("a saved run counts its run and rows with the workspace attributes")
    void savedRunIsCounted() {
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("saved");
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics", "workspace_id", WORKSPACE_ID))
                .isEqualTo(TRACES);
    }

    @Test
    @DisplayName("a saved run then labels the rest of its window, after the MySQL commit and its own insert, until "
            + "its timeout less the slack")
    void savedRunLabelsTheWindow() {
        answer(FIXTURE.answer);
        var timeout = TestConfigUtils.loadConfigTest().getTopics().getBuild().getProjectTimeout().toJavaDuration();
        var earliest = Instant.now().plus(timeout).minus(TopicsRebuildService.LABEL_SLACK);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        var latest = Instant.now().plus(timeout).minus(TopicsRebuildService.LABEL_SLACK);
        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        var deadline = ArgumentCaptor.forClass(Instant.class);
        var order = inOrder(runDAO, traceTopicsDAO, labelService);
        order.verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(runId), any(), any());
        order.verify(traceTopicsDAO).insert(any());
        order.verify(labelService).labelWindow(eq(WORKSPACE_ID), eq(projectId), deadline.capture(),
                eq(TopicWindowLabelService.Trigger.RUN));
        assertThat(deadline.getValue()).isBetween(earliest, latest);
    }

    @Test
    @DisplayName("a run that fails before its save does not label")
    void failedRunDoesNotLabel() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);
        answer(InferenceJson.MAPPER.getNodeFactory().textNode("Sorry, I cannot name these clusters."));

        service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        verify(labelService, never()).labelWindow(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a run's stats say how many traces its window had and how many it read")
    void statsSayWindowAndSample() throws Exception {
        when(windowDAO.countByProject(eq(WORKSPACE_ID), eq(Set.of(projectId)), any(), any(), anyLong()))
                .thenReturn(Flux.just(new TopicWindowDAO.ProjectCount(projectId, 250_000)));
        answer(FIXTURE.answer);

        service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        var stats = ArgumentCaptor.forClass(String.class);
        verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(runId), any(), stats.capture());
        var json = JsonUtils.getMapper().readTree(stats.getValue());
        assertThat(json.get("window_traces").asLong()).isEqualTo(250_000);
        assertThat(json.get("sample_traces").asLong()).isEqualTo(TRACES);
    }

    @Test
    @DisplayName("a saved run records the Other share it saved in its stats, once, with its workspace")
    void otherShareIsTheSavedValue() throws Exception {
        answer(FIXTURE.answer);

        service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        var stats = ArgumentCaptor.forClass(String.class);
        verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(runId), any(), stats.capture());
        double saved = JsonUtils.getMapper().readTree(stats.getValue()).get("other_share").asDouble();
        assertThat(meter.points("topics.run.other_share")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(saved);
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
    }

    /**
     * Four texts, three traces each, so each text is its own group and its vector is the group's centre. The answer
     * names the topic for the group of {@code alpha}: {@code beta} and {@code gamma} are Other groups within 0.95 of it
     * (gated), {@code delta} is an Other group far from it (kept).
     */
    @Test
    @DisplayName("the gated Other prototypes are not saved, and the saved rows assign as the build did")
    void gatedOtherIsNotSaved() throws Exception {
        int d = TopicsRebuildService.DIMENSIONS;
        var alpha = unit(d, 0, 1, 0, 0);
        var gated = List.of(unit(d, 0, 0.97, 1, Math.sqrt(1 - 0.97 * 0.97)),
                unit(d, 0, 0.96, 2, Math.sqrt(1 - 0.96 * 0.96)));
        var delta = unit(d, 3, 1, 0, 0);
        var texts = List.of("alpha request", "beta request", "gamma request", "delta request");
        var vectors = List.of(alpha, gated.get(0), gated.get(1), delta);
        var rows = new ArrayList<TopicWindowRow>();
        for (int i = 0; i < 12; i++) {
            rows.add(TopicWindowRow.builder()
                    .traceId(UUID.randomUUID())
                    .traceCreatedAt(FIXTURE.night.minusSeconds(3_600L * (i + 1)))
                    .taskVersion(1)
                    .text(texts.get(i % 4))
                    .vector(vectors.get(i % 4).clone())
                    .build());
        }
        when(windowDAO.readWindow(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.fromIterable(rows));
        var cluster = java.util.regex.Pattern.compile("cluster (\\d+): \\d+% of traffic\n    - alpha request");
        when(client.generate(any(), any())).thenAnswer(invocation -> {
            var prompt = invocation.<GenerateRequest>getArgument(0).messages().getFirst().content();
            var matcher = cluster.matcher(prompt);
            assertThat(matcher.find()).isTrue();
            var output = InferenceJson.MAPPER.readTree("{\"topics\": [{\"name\": \"Alpha Requests\", "
                    + "\"definition\": \"d\"}], \"assignment\": [{\"cluster\": " + matcher.group(1)
                    + ", \"topic\": 0}]}");
            return Mono.just(GenerateResponse.builder().requestId("r-1").usage(new Usage(1_000, 200))
                    .latencyMs(1_500).result(new GenerateResult(output)).build());
        });

        assertThat(service.firstBuild(runId, WORKSPACE_ID, projectId, night).status())
                .isEqualTo(TopicRun.Status.SAVED);

        var stats = ArgumentCaptor.forClass(String.class);
        verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(runId), any(), stats.capture());
        var json = JsonUtils.getMapper().readTree(stats.getValue());
        assertThat(json.get("other_groups").asInt()).isEqualTo(3);
        assertThat(json.get("gated_other").asInt()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TopicPrototype>> saved = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertPrototypes(saved.capture());
        var topicRows = saved.getValue().stream().filter(row -> row.topicId() != null).map(TopicPrototype::values)
                .toList();
        var otherRows = saved.getValue().stream().filter(row -> row.topicId() == null).map(TopicPrototype::values)
                .toList();
        assertThat(topicRows).hasSize(1);
        assertThat(otherRows).singleElement().satisfies(other -> assertThat(other).containsExactly(delta));
        for (var other : otherRows) {
            for (var topic : topicRows) {
                assertThat(dot(other, topic)).isLessThan(PrototypeAssigner.OTHER_GATE);
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
            savedLabels[i] = TraceTopic.OTHER.equals(traceTopic.topicId()) ? PrototypeAssigner.OTHER : 0;
        }
        var withoutGated = new PrototypeAssigner(topicRows, List.of(0), otherRows, d);
        var withGated = new PrototypeAssigner(topicRows, List.of(0),
                java.util.stream.Stream.concat(otherRows.stream(), gated.stream()).toList(), d);
        assertThat(withoutGated.assign(flat)).containsExactly(savedLabels);
        assertThat(withGated.assign(flat)).containsExactly(savedLabels);
        assertThat(savedLabels).containsExactly(0, 0, 0, PrototypeAssigner.OTHER, 0, 0, 0, PrototypeAssigner.OTHER, 0,
                0, 0, PrototypeAssigner.OTHER);
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
        int d = TopicsRebuildService.DIMENSIONS;
        var unit = unit(d, 0, 0.6, 1, 0.8);
        assertThat(TopicsRebuildService.isUnit(unit)).isTrue();
        var nearUnit = unit.clone();
        nearUnit[0] *= 1.0005f;
        assertThat(TopicsRebuildService.isUnit(nearUnit)).isTrue();

        var tooLong = unit.clone();
        tooLong[0] = 0.7f;
        assertThat(TopicsRebuildService.isUnit(tooLong)).isFalse();
        var nan = unit.clone();
        nan[2] = Float.NaN;
        assertThat(TopicsRebuildService.isUnit(nan)).isFalse();
        var infinite = unit.clone();
        infinite[2] = Float.POSITIVE_INFINITY;
        assertThat(TopicsRebuildService.isUnit(infinite)).isFalse();
        assertThat(TopicsRebuildService.isUnit(new float[]{0.6f, 0.8f})).isFalse();
    }

    @ParameterizedTest(name = "{0} unique texts make {1} groups")
    @CsvSource({"1068, 35", "481, 30", "4622, 50", "20, 20"})
    @DisplayName("fine groups: one per 30 unique texts, at least 30, at most 50 or the unique texts")
    void groups(int uniqueTexts, int expectedGroups) {
        assertThat(TopicsRebuildService.groups(uniqueTexts)).isEqualTo(expectedGroups);
    }

    @Test
    @DisplayName("texts per group: round(250 / k), from 5 to 10")
    void budget() {
        assertThat(TopicsRebuildService.authorExampleCount(50)).isEqualTo(5);
        assertThat(TopicsRebuildService.authorExampleCount(35)).isEqualTo(7);
        assertThat(TopicsRebuildService.authorExampleCount(30)).isEqualTo(8);
        assertThat(TopicsRebuildService.authorExampleCount(15)).isEqualTo(10);
    }

    @Test
    @DisplayName("the window read stops after 120 s, or after a shorter run timeout")
    void readSeconds() {
        var build = TestConfigUtils.loadConfigTest().getTopics().getBuild();
        assertThat(TopicsRebuildService.readSeconds(build)).isEqualTo(120);

        build.setProjectTimeout(Duration.minutes(1));
        assertThat(TopicsRebuildService.readSeconds(build)).isEqualTo(60);
    }

    private void answer(JsonNode output) {
        when(client.generate(any(), any())).thenReturn(Mono.just(GenerateResponse.builder()
                .requestId("r-1")
                .usage(new Usage(1_000, 200))
                .latencyMs(1_500)
                .result(new GenerateResult(output))
                .build()));
    }

    private static List<TopicWindowRow> window() {
        var rows = new ArrayList<TopicWindowRow>(TRACES);
        for (int i = 0; i < TRACES; i++) {
            var vector = new float[TopicsFixture.DIMENSIONS];
            System.arraycopy(FIXTURE.vectors, i * TopicsFixture.DIMENSIONS, vector, 0, TopicsFixture.DIMENSIONS);
            rows.add(TopicWindowRow.builder()
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
