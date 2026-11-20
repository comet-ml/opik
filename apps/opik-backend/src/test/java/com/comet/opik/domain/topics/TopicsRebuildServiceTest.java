package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.inference.GenerateRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResult;
import com.comet.opik.infrastructure.inference.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.dropwizard.util.Duration;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private Function<TopicAlgorithm, TopicsRebuildService> serviceWith;

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
        when(topicDAO.lockProject(anyString(), any())).thenReturn(1);
        when(topicDAO.saveList(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(runDAO.markSaved(any(), any(), any(), any())).thenReturn(1);
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.just((long) TRACES));
        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
        var metrics = new TopicsMetrics(meter.meter, workspaceId -> WORKSPACE_NAME);
        serviceWith = algorithm -> new TopicsRebuildService(TestConfigUtils.loadConfigTest().getTopics(), template,
                windowDAO, traceTopicsDAO, client, idGenerator, metrics, labelService, algorithm);
        service = serviceWith.apply(TopicsTestAlgorithm.algorithm());
    }

    @Test
    @DisplayName("a start that finds an attempt left running past the lease counts it abandoned once, then starts")
    void startCountsAnAbandonedAttempt() {
        var abandonedBefore = Instant.now().minusSeconds(3_600);
        when(runDAO.recordAbandoned(eq(WORKSPACE_ID), eq(projectId), eq(abandonedBefore), any())).thenReturn(1, 0);

        var started = service.startRun(WORKSPACE_ID, projectId, TopicProject.RunMode.FIRST_BUILD, abandonedBefore);
        service.startRun(WORKSPACE_ID, projectId, TopicProject.RunMode.FIRST_BUILD, abandonedBefore);

        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("abandoned");
        });
        var order = inOrder(runDAO);
        order.verify(runDAO).recordAbandoned(eq(WORKSPACE_ID), eq(projectId), eq(abandonedBefore), any());
        order.verify(runDAO).start(eq(WORKSPACE_ID), eq(projectId), eq(started),
                eq(TopicProject.RunMode.FIRST_BUILD), any());
    }

    @Test
    @DisplayName("a run that had already ended is not counted again")
    void alreadyEndedIsNotCounted() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any())).thenReturn(0);

        assertThat(service.fail(runId, WORKSPACE_ID, projectId, TopicProject.RunError.TIMEOUT)).isFalse();

        assertThat(meter.points("topics.runs")).isEmpty();
    }

    @Test
    @DisplayName("a mark that throws counts nothing, as the run's row stays running; the mark that ends the row "
            + "counts it once, with its workspace")
    void markThatThrowsCountsNothing() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any()))
                .thenThrow(new IllegalStateException("MySQL down"))
                .thenReturn(1);

        assertThatThrownBy(() -> service.fail(runId, WORKSPACE_ID, projectId, TopicProject.RunError.LLM_ERROR))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meter.points("topics.runs")).isEmpty();

        assertThat(service.fail(runId, WORKSPACE_ID, projectId, TopicProject.RunError.TIMEOUT)).isTrue();
        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("timeout");
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
    }

    @Test
    @DisplayName("an answer that cannot be read counts bad_answer, not ok, fails the run once, and the failed run "
            + "does not label")
    void unreadableAnswerIsBadAnswer() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        answer(InferenceJson.MAPPER.getNodeFactory().textNode("Sorry, I cannot name these clusters."));

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.errorCode()).isEqualTo("llm_bad_answer");
        assertThat(meter.points("topics.qwen.calls")).singleElement().satisfies(point -> {
            assertThat(point.get("call")).isEqualTo("author");
            assertThat(point.get("outcome")).isEqualTo("bad_answer");
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.sum("topics.runs", "status", "failed", "error_code", "llm_bad_answer")).isEqualTo(1);
        assertThat(meter.points("topics.run.duration_seconds"))
                .allSatisfy(point -> assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID));
        verify(labelService, never()).labelWindow(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a window read that trips a ClickHouse guard fails the run with window_read, before Qwen and the save")
    void windowReadGuardHit() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        when(windowDAO.readWindow(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.error(new IllegalStateException(
                        "Code: 158. DB::Exception: Limit for rows (controlled by 'max_rows_to_read' setting) exceeded")));

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("window_read");
        verify(runDAO).markFailed(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(),
                eq(TopicProject.RunError.WINDOW_READ));
        verify(client, never()).generate(any(), any());
        verify(topicDAO, never()).insertTopics(any());
        verify(traceTopicsDAO, never()).insert(any());
        assertThat(meter.sum("topics.runs", "status", "failed", "error_code", "window_read")).isEqualTo(1);
    }

    @Test
    @DisplayName("a MySQL save that fails writes no trace_topics row and counts the run failed once")
    void failedSaveWritesNoLabels() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any())).thenReturn(1);
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
    @DisplayName("a second first save of the project fails as a conflict on the saved run and writes nothing")
    void secondSaveIsConflict() {
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), any())).thenReturn(1);
        when(topicDAO.saveList(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.errorCode()).isEqualTo("save");
        verify(topicDAO).saveList(eq(WORKSPACE_ID), eq(projectId), isNull(), eq(runId), any(), any(), any(),
                eq(TopicEmbedding.MODEL));
        verify(runDAO).markFailed(eq(WORKSPACE_ID), eq(projectId), eq(runId), any(), eq(TopicProject.RunError.SAVE));
        verify(topicDAO, never()).insertTopics(any());
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

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(3);
        var order = inOrder(runDAO, traceTopicsDAO);
        order.verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(projectId), eq(runId), any());
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

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(1 + TopicsRebuildService.INSERT_RETRIES);
        verify(runDAO, never()).markFailed(any(), any(), any(), any(), any());
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

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
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

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(interrupted).isTrue();
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics")).isEqualTo(TRACES);
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
        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        var deadline = ArgumentCaptor.forClass(Instant.class);
        var order = inOrder(runDAO, traceTopicsDAO, labelService);
        order.verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(projectId), eq(runId), any());
        order.verify(traceTopicsDAO).insert(any());
        order.verify(labelService).labelWindow(eq(WORKSPACE_ID), eq(projectId), deadline.capture(),
                eq(TopicWindowLabelService.Trigger.RUN));
        assertThat(deadline.getValue()).isBetween(earliest, latest);
    }

    @Test
    @DisplayName("a run's stats say how many traces its window had and how many it read")
    void statsSayWindowAndSample() throws Exception {
        when(windowDAO.countByProject(eq(WORKSPACE_ID), eq(Set.of(projectId)), any(), any(), anyLong()))
                .thenReturn(Flux.just(new TopicWindowDAO.ProjectCount(projectId, 250_000)));
        answer(FIXTURE.answer);

        var json = service.firstBuild(runId, WORKSPACE_ID, projectId, night).stats();

        assertThat(json.get("window_traces").asLong()).isEqualTo(250_000);
        assertThat(json.get("sample_traces").asLong()).isEqualTo(TRACES);
    }

    @Test
    @DisplayName("a saved nightly update records the Other share it logged in its stats, and its live topics and churn, "
            + "with its workspace")
    void nightlyUpdateIsCounted() {
        firstBuildThenEmptyNaming();

        var outcome = service.nightlyUpdate(UUID.randomUUID(), WORKSPACE_ID, projectId, night.plusDays(1));

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        var stats = outcome.stats();
        assertThat(stats.path("mode").asText()).isEqualTo("nightly_update");
        assertThat(stats.get("window_traces").asLong()).isEqualTo(TRACES);
        assertThat(stats.get("sample_traces").asLong()).isEqualTo(TRACES);
        // The first build's point, then the nightly update's: the value it logged, not computed again.
        assertThat(meter.points("topics.run.other_share")).hasSize(2).last().satisfies(point -> {
            assertThat(point.value()).isEqualTo(stats.get("other_share").asDouble());
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.points("topics.topics.live")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(stats.get("topics").asDouble());
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
        });
        for (var name : List.of("topics.run.churn", "topics.run.churn_any")) {
            assertThat(meter.points(name)).as(name).singleElement().satisfies(point -> {
                assertThat(point.value()).isEqualTo(stats.get(name.substring("topics.run.".length())).asDouble());
                assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
                assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
            });
        }
        assertThat(meter.sum("topics.runs", "status", "saved", "workspace_id", WORKSPACE_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("a nightly update saves the list by compare-and-set on the run it read, updates the kept topics, "
            + "writes its labels as a run's, then runs the labelling pass after its insert")
    @SuppressWarnings("unchecked")
    void nightlyUpdateSavesThenLabels() {
        var built = firstBuildThenEmptyNaming();
        var updateRunId = UUID.randomUUID();

        var outcome = service.nightlyUpdate(updateRunId, WORKSPACE_ID, projectId, night.plusDays(1));

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        verify(topicDAO).saveList(eq(WORKSPACE_ID), eq(projectId), eq(runId), eq(updateRunId), any(),
                eq(night.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()), any(), eq(TopicEmbedding.MODEL));
        ArgumentCaptor<List<Topic>> updated = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).updateTopics(updated.capture());
        assertThat(updated.getValue()).extracting(Topic::id)
                .containsExactlyElementsOf(built.stream().map(Topic::id).toList());
        verify(topicDAO, never()).deleteTopics(any(), any(), any());
        verify(topicDAO, never()).resetList(any(), any(), any(), any());
        ArgumentCaptor<List<TraceTopic>> rows = ArgumentCaptor.forClass(List.class);
        verify(traceTopicsDAO, times(2)).insert(rows.capture());
        assertThat(rows.getValue()).hasSize(TRACES)
                .allSatisfy(row -> {
                    assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
                    assertThat(row.runId()).isEqualTo(updateRunId);
                });
        var order = inOrder(topicDAO, runDAO, traceTopicsDAO, labelService);
        order.verify(topicDAO).lockProject(WORKSPACE_ID, projectId);
        order.verify(topicDAO).saveList(eq(WORKSPACE_ID), eq(projectId), eq(runId), eq(updateRunId), any(), any(),
                any(), any());
        order.verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(projectId), eq(updateRunId), any());
        order.verify(traceTopicsDAO).insert(any());
        order.verify(labelService).labelWindow(eq(WORKSPACE_ID), eq(projectId), any(),
                eq(TopicWindowLabelService.Trigger.RUN));
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
        when(topicDAO.findByProject(WORKSPACE_ID, projectId)).thenReturn(far);
        when(topicDAO.resetList(eq(WORKSPACE_ID), eq(projectId), eq(runId), any())).thenReturn(1);
        var updateRunId = UUID.randomUUID();

        var outcome = service.nightlyUpdate(updateRunId, WORKSPACE_ID, projectId, night.plusDays(1));

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        var order = inOrder(topicDAO, runDAO);
        order.verify(topicDAO).resetList(eq(WORKSPACE_ID), eq(projectId), eq(runId), any());
        order.verify(topicDAO).deleteTopics(WORKSPACE_ID, projectId, far.stream().map(Topic::id).toList());
        order.verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(projectId), eq(updateRunId), any());
        verify(topicDAO, never()).saveList(any(), any(), eq(runId), any(), any(), any(), any(), any());
        verify(topicDAO, never()).updateTopics(any());
        assertThat(meter.sum("topics.retired", "reason", "low_share", "workspace_id", WORKSPACE_ID))
                .isEqualTo(far.size());
        // The first build's insert and pass only.
        verify(traceTopicsDAO, times(1)).insert(any());
        verify(labelService, times(1)).labelWindow(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"another run saved the project meanwhile", "MySQL is down"})
    @DisplayName("a nightly update whose MySQL save fails, as a conflict or with MySQL down, fails with save and "
            + "writes no trace_topics row: the traces keep last night's")
    void failedUpdateSaveWritesNoLabels(String failure) {
        firstBuildThenEmptyNaming();
        var save = when(topicDAO.saveList(any(), any(), eq(runId), any(), any(), any(), any(), any()));
        if (failure.equals("MySQL is down")) {
            save.thenThrow(new IllegalStateException("MySQL down"));
        } else {
            save.thenReturn(0);
        }
        var updateRunId = UUID.randomUUID();
        when(runDAO.markFailed(eq(WORKSPACE_ID), eq(projectId), eq(updateRunId), any(), any())).thenReturn(1);

        var outcome = service.nightlyUpdate(updateRunId, WORKSPACE_ID, projectId, night.plusDays(1));

        assertThat(outcome.errorCode()).isEqualTo("save");
        verify(topicDAO, never()).updateTopics(any());
        verify(runDAO, never()).markSaved(eq(WORKSPACE_ID), eq(projectId), eq(updateRunId), any());
        // The first build's insert and pass only.
        verify(traceTopicsDAO, times(1)).insert(any());
        verify(labelService, times(1)).labelWindow(any(), any(), any(), any());
        assertThat(meter.sum("topics.runs", "status", "failed", "error_code", "save")).isEqualTo(1);
    }

    @Test
    @DisplayName("a nightly update's trace_topics insert comes after its MySQL commit, and a failed insert is retried")
    void updateInsertAfterCommitIsRetried() {
        firstBuildThenEmptyNaming();
        var attempts = new AtomicInteger();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.defer(() -> attempts.incrementAndGet() <= 2
                ? Mono.error(new IllegalStateException("ClickHouse down"))
                : Mono.just((long) TRACES)));
        var updateRunId = UUID.randomUUID();

        var outcome = service.nightlyUpdate(updateRunId, WORKSPACE_ID, projectId, night.plusDays(1));

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(attempts).hasValue(3);
        var order = inOrder(runDAO, traceTopicsDAO);
        order.verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(projectId), eq(updateRunId), any());
        order.verify(traceTopicsDAO).insert(any());
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics")).isEqualTo(2 * TRACES);
    }

    @Test
    @DisplayName("a nightly update whose trace_topics insert fails every attempt stays saved, and its labelling pass "
            + "still runs")
    void updateInsertExhausted() {
        firstBuildThenEmptyNaming();
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.error(new IllegalStateException("ClickHouse down")));
        var updateRunId = UUID.randomUUID();

        var outcome = service.nightlyUpdate(updateRunId, WORKSPACE_ID, projectId, night.plusDays(1));

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        verify(runDAO).markSaved(eq(WORKSPACE_ID), eq(projectId), eq(updateRunId), any());
        // The first build's rows only.
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics")).isEqualTo(TRACES);
        verify(labelService, times(2)).labelWindow(eq(WORKSPACE_ID), eq(projectId), any(),
                eq(TopicWindowLabelService.Trigger.RUN));
    }

    @Test
    @DisplayName("an answer the algorithm's read returns null for counts the call bad_answer under its kind, generate "
            + "returns null, and the nightly update saves")
    void readReturnsNull() {
        var kmeans = TopicsTestAlgorithm.algorithm();
        var answers = new ArrayList<Object>();
        var stub = new TopicAlgorithm() {
            @Override
            public FirstBuild firstBuild(TopicWindow window, int maxTopics, TopicRunContext context) {
                return kmeans.firstBuild(window, maxTopics, context);
            }

            @Override
            public TopicUpdate update(NightState state, NightWindow window, int maxTopics,
                    TopicRunContext context) {
                answers.add(Objects.requireNonNullElse(context.generate(LlmCall.NAMING, "naming", "prompt",
                        InferenceJson.MAPPER.createObjectNode(), 10, output -> null), "null"));
                var labels = new int[window.size()];
                Arrays.fill(labels, OTHER);
                return new TopicUpdate(labels, labels);
            }

            @Override
            public Optional<TopicLabeller> labeller(List<Topic> topics, PrototypeVectors otherVectors) {
                return kmeans.labeller(topics, otherVectors);
            }
        };
        var withStub = serviceWith.apply(stub);
        answer(FIXTURE.answer);
        withStub.firstBuild(runId, WORKSPACE_ID, projectId, night);
        readBackFirstBuild();
        answer(TextNode.valueOf("Sorry, I cannot name these groups."));

        var outcome = withStub.nightlyUpdate(UUID.randomUUID(), WORKSPACE_ID, projectId, night.plusDays(1));

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(answers).containsExactly("null");
        assertThat(meter.sum("topics.qwen.calls", "call", "naming", "outcome", "bad_answer")).isEqualTo(1);
        assertThat(meter.sum("topics.qwen.calls", "call", "naming", "outcome", "ok")).isZero();
    }

    @Test
    @DisplayName("born, retired and churn keep their own attributes next to the workspace's")
    void lifecycleMetricsHaveTheWorkspace() {
        var meter = new TopicsTestMeter();
        var metrics = new TopicsMetrics(meter.meter, workspaceId -> WORKSPACE_NAME);

        metrics.topics(WORKSPACE_ID, 7, 2);
        metrics.topics(WORKSPACE_ID, 7, 0);
        metrics.retired(WORKSPACE_ID, NightState.RetiredReason.LOW_SHARE);
        metrics.churn(WORKSPACE_ID, new TopicChurn.Churn(0.04, 0.1));

        assertThat(meter.points("topics.topics.born")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(2);
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.points("topics.topics.live")).hasSize(2)
                .allSatisfy(point -> assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME));
        assertThat(meter.points("topics.retired")).singleElement().satisfies(point -> {
            assertThat(point.get("reason")).isEqualTo("low_share");
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.points("topics.run.churn")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(0.04);
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
        });
        assertThat(meter.points("topics.run.churn_any")).singleElement()
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
        verify(topicDAO).saveList(eq(WORKSPACE_ID), eq(projectId), isNull(), eq(runId), any(),
                windowUntil.capture(), otherVectors.capture(), eq(TopicEmbedding.MODEL));
        verify(traceTopicsDAO).insert(any());
        var project = TopicProject.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .savedRuns(1)
                .listRunId(runId)
                .windowUntil(windowUntil.getValue())
                .otherVectors(otherVectors.getValue())
                .embedModel(TopicEmbedding.MODEL)
                .build();
        when(topicDAO.findProjects(eq(WORKSPACE_ID), any())).thenReturn(List.of(project));
        when(topicDAO.findByProject(WORKSPACE_ID, projectId)).thenReturn(topics.getValue());
        return topics.getValue();
    }

    /**
     * The first build, read back as the nightly update reads it, then a naming answer that changes nothing: most nights
     * make no Qwen call, and any that comes changes nothing. Returns the first build's topics.
     */
    private List<Topic> firstBuildThenEmptyNaming() {
        answer(FIXTURE.answer);
        service.firstBuild(runId, WORKSPACE_ID, projectId, night);
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

    @Test
    @DisplayName("a saved run counts its run and rows, and records the Other share it saved in its stats, once, "
            + "with its workspace")
    void otherShareIsTheSavedValue() throws Exception {
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(meter.points("topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.get("status")).isEqualTo("saved");
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.sum("topics.save.rows", "table", "trace_topics", "workspace_id", WORKSPACE_ID))
                .isEqualTo(TRACES);
        double saved = outcome.stats().get("other_share").asDouble();
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
        var rows = new ArrayList<TopicWindowDAO.WindowRow>();
        for (int i = 0; i < 12; i++) {
            rows.add(TopicWindowDAO.WindowRow.builder()
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
            return Mono.just(response(output));
        });

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(outcome.stats().get("other_groups").asInt()).isEqualTo(3);
        assertThat(outcome.stats().get("gated_other").asInt()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Topic>> savedTopics = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertTopics(savedTopics.capture());
        var savedOther = ArgumentCaptor.forClass(PrototypeVectors.class);
        verify(topicDAO).saveList(any(), any(), any(), any(), any(), any(), savedOther.capture(), any());
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
                java.util.stream.Stream.concat(otherRows.stream(), gated.stream()).toList(), d,
                config.getAssignMinCosine(), config.getOtherGate());
        assertThat(withoutGated.assign(flat)).containsExactly(savedLabels);
        assertThat(withGated.assign(flat)).containsExactly(savedLabels);
        assertThat(savedLabels).containsExactly(0, 0, 0, TopicAlgorithm.OTHER, 0, 0, 0, TopicAlgorithm.OTHER, 0,
                0, 0, TopicAlgorithm.OTHER);
    }

    @Test
    @DisplayName("the service saves what the bound algorithm returns: its topics, prototypes in order, labels and stats")
    void savesWhatTheAlgorithmReturns() throws Exception {
        int d = TopicsRebuildService.DIMENSIONS;
        var first = unit(d, 0, 1, 1, 0);
        var second = unit(d, 1, 1, 0, 0);
        var other = unit(d, 2, 1, 0, 0);
        var stub = new TopicAlgorithm() {
            @Override
            public FirstBuild firstBuild(TopicWindow window, int maxTopics, TopicRunContext context) {
                context.stats().put("stub_stat", maxTopics);
                var labels = new int[window.size()];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = i % 3 == 2 ? OTHER : i % 3;
                }
                return FirstBuild.builder()
                        .topics(List.of(NewTopic.builder().name("First").definition("one").build(),
                                NewTopic.builder().name("Second").definition("two").build()))
                        .labels(labels)
                        .topicPrototypes(List.of(second, first))
                        .owners(List.of(1, 0))
                        .otherPrototypes(List.of(other))
                        .build();
            }

            @Override
            public TopicUpdate update(NightState state, NightWindow window, int maxTopics,
                    TopicRunContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<TopicLabeller> labeller(List<Topic> topics, PrototypeVectors otherVectors) {
                return Optional.empty();
            }
        };

        var outcome = serviceWith.apply(stub).firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        verify(client, never()).generate(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Topic>> topics = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertTopics(topics.capture());
        assertThat(topics.getValue()).extracting(Topic::name, Topic::definition, Topic::size)
                .containsExactly(tuple("First", "one", TRACES / 3), tuple("Second", "two", TRACES / 3));
        var firstId = topics.getValue().get(0).id();
        var secondId = topics.getValue().get(1).id();
        assertThat(topics.getValue()).extracting(Topic::vectors)
                .containsExactly(new PrototypeVectors(List.of(first)), new PrototypeVectors(List.of(second)));
        verify(topicDAO).saveList(eq(WORKSPACE_ID), eq(projectId), isNull(), eq(runId), any(), any(),
                eq(new PrototypeVectors(List.of(other))), eq(TopicEmbedding.MODEL));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TraceTopic>> traceTopics = ArgumentCaptor.forClass(List.class);
        verify(traceTopicsDAO).insert(traceTopics.capture());
        assertThat(traceTopics.getValue().stream().collect(Collectors.groupingBy(TraceTopic::topicId,
                Collectors.counting()))).isEqualTo(Map.of(firstId, (long) TRACES / 3, secondId, (long) TRACES / 3,
                        TraceTopic.OTHER, (long) TRACES / 3));
        var json = outcome.stats();
        assertThat(json.get("stub_stat").asInt()).isEqualTo(TopicsRebuildService.MAX_TOPICS);
        assertThat(json.get("topics").asInt()).isEqualTo(2);
        assertThat(json.get("other_share").asDouble()).isEqualTo(1.0 / 3);
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
        // The embedding model's output size, independent of the constant the code reads.
        assertThat(d).isEqualTo(384);
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

    @Test
    @DisplayName("the window read stops after 120 s, or after a shorter run timeout")
    void readSeconds() {
        var build = TestConfigUtils.loadConfigTest().getTopics().getBuild();
        assertThat(TopicsRebuildService.readSeconds(build)).isEqualTo(120);

        build.setProjectTimeout(Duration.minutes(1));
        assertThat(TopicsRebuildService.readSeconds(build)).isEqualTo(60);
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
