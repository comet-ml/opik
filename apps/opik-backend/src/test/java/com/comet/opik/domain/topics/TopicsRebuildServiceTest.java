package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.IdGenerator;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(topicDAO.findStates(any(), any())).thenReturn(List.of());
        when(topicDAO.lockProject(anyString(), any())).thenReturn(1);
        when(runDAO.markSaved(any(), any(), any(), any())).thenReturn(1);
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.just((long) TRACES));
        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
        var names = new TopicsWorkspaceNames(workspaceId -> WORKSPACE_NAME, Clock.systemUTC());
        service = new TopicsRebuildService(TestConfigUtils.loadConfigTest().getTopics(), template, windowDAO,
                traceTopicsDAO, client, idGenerator, new TopicsMetrics(meter.meter, names));
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
    @DisplayName("a stalled insert attempt stops after 120 s and is retried; when every attempt stalls, the run stays "
            + "saved and its thread is freed")
    void stalledInsertStops() throws Exception {
        var attempts = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.never();
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

    @Test
    @DisplayName("fine groups: one per 30 unique texts, at least 30, at most 50 or the unique texts")
    void groups() {
        assertThat(TopicsRebuildService.groups(1068)).isEqualTo(35);
        assertThat(TopicsRebuildService.groups(481)).isEqualTo(30);
        assertThat(TopicsRebuildService.groups(4622)).isEqualTo(50);
        assertThat(TopicsRebuildService.groups(20)).isEqualTo(20);
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
        var nightly = TestConfigUtils.loadConfigTest().getTopics().getNightly();
        assertThat(TopicsRebuildService.readSeconds(nightly)).isEqualTo(120);

        nightly.setProjectTimeout(Duration.minutes(1));
        assertThat(TopicsRebuildService.readSeconds(nightly)).isEqualTo(60);
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
