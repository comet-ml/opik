package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResult;
import com.comet.opik.infrastructure.inference.Usage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a run counts, with every store mocked: each failed run once, Qwen outcomes after the answer is read, and the
 * take-back failure. A run builds the fixture's window, so its steps are the real ones.
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
    private TopicsWorkspaceNames names;
    private TopicRunDAO runDAO;
    private TopicDAO topicDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private InferenceClient client;
    private Function<TopicAlgorithm, TopicsRebuildService> serviceWith;
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
        var windowDAO = mock(TopicWindowDAO.class);
        when(windowDAO.readWindow(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.fromIterable(window()));
        when(topicDAO.findStates(any())).thenReturn(List.of());
        when(topicDAO.lockProject(anyString(), any())).thenReturn(1);
        when(runDAO.markSaved(any(), any(), any())).thenReturn(1);
        when(traceTopicsDAO.insert(any())).thenReturn(Mono.just((long) TRACES));
        when(traceTopicsDAO.deleteByRunId(anyString(), any(), any())).thenReturn(Mono.just(0L));
        // Every trace of the window is one Logs lists.
        when(traceTopicsDAO.findLogsTraceIds(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyLong()))
                .thenReturn(Mono.just(window().stream().map(TopicWindowRow::traceId).collect(Collectors.toSet())));
        var idGenerator = mock(IdGenerator.class);
        when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
        names = new TopicsWorkspaceNames(workspaceId -> WORKSPACE_NAME, Clock.systemUTC());
        serviceWith = algorithm -> new TopicsRebuildService(new TopicsConfig(), template, windowDAO, traceTopicsDAO,
                client, idGenerator, new TopicsMetrics(meter.meter, names), algorithm);
        service = serviceWith.apply(new KMeansTopicAlgorithm());
    }

    @Test
    @DisplayName("a failed mark counts the run once, with its workspace")
    void failCountsOnce() {
        when(runDAO.markFailed(eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);

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
        when(runDAO.markFailed(eq(runId), any(), anyString(), anyString(), any())).thenReturn(0);

        assertThat(service.fail(runId, WORKSPACE_ID, TopicsRunException.Code.TIMEOUT, "m", null)).isFalse();

        assertThat(meter.points("topics.runs")).isEmpty();
    }

    @Test
    @DisplayName("a mark that throws counts nothing, as the run's row stays running; the mark that ends the row "
            + "counts it once")
    void markThatThrowsCountsNothing() {
        when(runDAO.markFailed(eq(runId), any(), anyString(), anyString(), any()))
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
        when(runDAO.markFailed(eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);
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
    @DisplayName("a failed save whose trace_topics delete fails too counts the take-back failure and the run")
    void takeBackFailureIsCounted() {
        when(runDAO.markFailed(eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);
        when(topicDAO.lockProject(anyString(), any())).thenThrow(new IllegalStateException("MySQL down"));
        when(traceTopicsDAO.deleteByRunId(anyString(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("ClickHouse down")));
        answer(FIXTURE.answer);

        var outcome = service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.errorCode()).isEqualTo("save");
        assertThat(meter.sum("topics.qwen.calls", "outcome", "ok")).isEqualTo(1);
        assertThat(meter.points("topics.save.take_back_failures")).singleElement().satisfies(point -> {
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("error_type")).isNotBlank();
        });
        assertThat(meter.sum("topics.runs", "status", "failed", "error_code", "save")).isEqualTo(1);
        assertThat(meter.sum("topics.runs", "status", "saved")).isZero();
        assertThat(meter.points("topics.save.rows")).isEmpty();
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
        var topics = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertTopics(topics.capture());
        assertThat(meter.points("topics.topics.live")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(topics.getValue().size());
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.sum("topics.topics.born", "mode", "first_build", "workspace_id", WORKSPACE_ID,
                "workspace_name", WORKSPACE_NAME)).isEqualTo(topics.getValue().size());
    }

    @Test
    @DisplayName("a saved nightly update records the Other share it saved in its stats, and its live topics and churn, "
            + "with its workspace")
    void nightlyUpdateIsCounted() throws Exception {
        answer(FIXTURE.answer);
        service.firstBuild(runId, WORKSPACE_ID, projectId, night);
        readBackFirstBuild();
        // Most nights make no Qwen call; any that comes changes nothing.
        answer(emptyRevise());
        var updateRunId = UUID.randomUUID();

        var outcome = service.nightlyUpdate(updateRunId, WORKSPACE_ID, projectId, night.plusDays(1));

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        var stats = ArgumentCaptor.forClass(String.class);
        verify(runDAO).markSaved(eq(updateRunId), any(), stats.capture());
        var saved = JsonUtils.getMapper().readTree(stats.getValue());
        assertThat(saved.path("mode").asText()).isEqualTo("nightly_update");
        // The first build's point, then the nightly update's: the value it saved, not computed again.
        assertThat(meter.points("topics.run.other_share")).hasSize(2).last().satisfies(point -> {
            assertThat(point.value()).isEqualTo(saved.get("other_share").asDouble());
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.points("topics.topics.live")).hasSize(2).last().satisfies(point -> {
            assertThat(point.value()).isEqualTo(saved.get("topics").asDouble());
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
        });
        for (var name : List.of("topics.run.churn", "topics.run.churn_any")) {
            assertThat(meter.points(name)).as(name).singleElement().satisfies(point -> {
                assertThat(point.value()).isEqualTo(saved.get(name.substring("topics.run.".length())).asDouble());
                assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
                assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
            });
        }
        assertThat(meter.sum("topics.runs", "status", "saved", "workspace_id", WORKSPACE_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("born, retired and churn keep their own attributes next to the workspace's")
    void lifecycleMetricsHaveTheWorkspace() {
        var metrics = new TopicsMetrics(meter.meter, names);

        metrics.topics(WORKSPACE_ID, TopicRun.Mode.NIGHTLY_UPDATE, 7, 2);
        metrics.topics(WORKSPACE_ID, TopicRun.Mode.NIGHTLY_UPDATE, 7, 0);
        metrics.retired(WORKSPACE_ID, "cap");
        metrics.churn(WORKSPACE_ID, new TopicChurn.Churn(0.04, 0.1));

        assertThat(meter.points("topics.topics.born")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(2);
            assertThat(point.get("mode")).isEqualTo("nightly_update");
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        assertThat(meter.points("topics.topics.live")).hasSize(2)
                .allSatisfy(point -> assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME));
        assertThat(meter.points("topics.topics.retired")).singleElement().satisfies(point -> {
            assertThat(point.get("reason")).isEqualTo("cap");
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

    @Test
    @DisplayName("a first build whose algorithm labels a trace with a topic it did not return fails as invariant, "
            + "and saves nothing")
    void labelOutOfRangeIsInvariant() {
        when(runDAO.markFailed(eq(runId), any(), anyString(), anyString(), any())).thenReturn(1);
        var labels = new int[TRACES];
        labels[0] = 1;
        var prototype = new float[TopicsFixture.DIMENSIONS];
        System.arraycopy(FIXTURE.vectors, 0, prototype, 0, TopicsFixture.DIMENSIONS);
        var algorithm = new TopicAlgorithm() {
            @Override
            public FirstBuild firstBuild(NightWindow window, int maxTopics, Context context) {
                return FirstBuild.builder()
                        .topics(List
                                .of(NewTopic.builder().name("Billing").definition("Questions about invoices").build()))
                        .labels(labels)
                        .topicPrototypes(List.of(prototype))
                        .owners(List.of(0))
                        .otherPrototypes(List.of())
                        .build();
            }

            @Override
            public Update update(NightState state, NightWindow window, int maxTopics, Context context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Arrival> arrival(List<Topic> live, List<TopicPrototype> prototypes) {
                throw new UnsupportedOperationException();
            }
        };

        var outcome = serviceWith.apply(algorithm).firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.errorCode()).isEqualTo("invariant");
        verify(topicDAO, never()).insertTopics(any());
        verify(traceTopicsDAO, never()).insert(any());
    }

    @Test
    @DisplayName("a first build saves what its algorithm returns, with the algorithm's stats and phases; the service "
            + "makes no LLM call of its own")
    @SuppressWarnings("unchecked")
    void savesWhatTheAlgorithmReturns() throws Exception {
        var labels = new int[TRACES];
        for (int i = 0; i < TRACES; i++) {
            labels[i] = i % 2 == 0 ? 0 : PrototypeAssigner.OTHER;
        }
        var prototype = new float[TopicsFixture.DIMENSIONS];
        System.arraycopy(FIXTURE.vectors, 0, prototype, 0, TopicsFixture.DIMENSIONS);
        var algorithm = new TopicAlgorithm() {
            @Override
            public FirstBuild firstBuild(NightWindow window, int maxTopics, Context context) {
                assertThat(window.size()).isEqualTo(TRACES);
                context.stats().put("algorithm", "stub");
                return context.timed("stub", () -> FirstBuild.builder()
                        .topics(List
                                .of(NewTopic.builder().name("Billing").definition("Questions about invoices").build()))
                        .labels(labels)
                        .topicPrototypes(List.of(prototype))
                        .owners(List.of(0))
                        .otherPrototypes(List.of())
                        .build());
            }

            @Override
            public Update update(NightState state, NightWindow window, int maxTopics, Context context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Arrival> arrival(List<Topic> live, List<TopicPrototype> prototypes) {
                throw new UnsupportedOperationException();
            }
        };

        var outcome = serviceWith.apply(algorithm).firstBuild(runId, WORKSPACE_ID, projectId, night);

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        verify(client, never()).generate(any(), any());
        ArgumentCaptor<List<Topic>> topics = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertTopics(topics.capture());
        assertThat(topics.getValue()).singleElement().satisfies(topic -> {
            assertThat(topic.name()).isEqualTo("Billing");
            assertThat(topic.size()).isEqualTo(TRACES / 2);
        });
        ArgumentCaptor<List<TraceTopic>> rows = ArgumentCaptor.forClass(List.class);
        verify(traceTopicsDAO).insert(rows.capture());
        assertThat(rows.getValue()).hasSize(TRACES);
        assertThat(rows.getValue().stream().filter(row -> row.topicId().equals(TraceTopic.OTHER))).hasSize(TRACES / 2);
        var stats = ArgumentCaptor.forClass(String.class);
        verify(runDAO).markSaved(eq(runId), any(), stats.capture());
        var saved = JsonUtils.getMapper().readTree(stats.getValue());
        assertThat(saved.path("algorithm").asText()).isEqualTo("stub");
        assertThat(saved.path("seconds").has("stub")).isTrue();
        assertThat(saved.path("other_share").asDouble()).isEqualTo(0.5);
    }

    /** The list, prototypes, state and labels the first build saved, as the nightly update reads them back. */
    @SuppressWarnings("unchecked")
    private void readBackFirstBuild() {
        ArgumentCaptor<List<Topic>> topics = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertTopics(topics.capture());
        ArgumentCaptor<List<TopicPrototype>> prototypes = ArgumentCaptor.forClass(List.class);
        verify(topicDAO).insertPrototypes(prototypes.capture());
        var state = ArgumentCaptor.forClass(TopicProjectState.class);
        verify(topicDAO).insertState(state.capture());
        ArgumentCaptor<List<TraceTopic>> labels = ArgumentCaptor.forClass(List.class);
        verify(traceTopicsDAO).insert(labels.capture());
        when(topicDAO.findStates(any())).thenReturn(List.of(state.getValue()));
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(topics.getValue());
        when(topicDAO.findPrototypes(projectId)).thenReturn(prototypes.getValue());
        when(topicDAO.updateState(any(), eq(runId))).thenReturn(1);
        var previous = labels.getValue().stream()
                .collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId));
        when(traceTopicsDAO.findLabels(eq(WORKSPACE_ID), eq(projectId), any(), any(), eq(runId)))
                .thenReturn(Mono.just(previous));
    }

    /** A revise answer that changes nothing. */
    private static JsonNode emptyRevise() {
        var revise = InferenceJson.MAPPER.createObjectNode();
        revise.putArray("assign");
        revise.putArray("new");
        revise.putArray("other");
        return revise;
    }

    @Test
    @DisplayName("a saved run records the Other share it saved in its stats, once, with its workspace")
    void otherShareIsTheSavedValue() throws Exception {
        answer(FIXTURE.answer);

        service.firstBuild(runId, WORKSPACE_ID, projectId, night);

        var stats = ArgumentCaptor.forClass(String.class);
        verify(runDAO).markSaved(eq(runId), any(), stats.capture());
        double saved = JsonUtils.getMapper().readTree(stats.getValue()).get("other_share").asDouble();
        assertThat(meter.points("topics.run.other_share")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(saved);
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
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
