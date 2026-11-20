package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsMetricsReader.metrics;
import static com.comet.opik.domain.topics.TopicsMetricsReader.points;
import static com.comet.opik.domain.topics.TopicsMetricsReader.sum;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.domain.topics.TopicsTestData.answer;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The rebuild service end to end: the window read from ClickHouse, the save to MySQL, the {@code trace_topics} insert
 * and opik-inference stubbed in WireMock. Each test has its own workspace and project. Failures that need a store to
 * fail on cue are in {@link TopicsRebuildServiceFaultsTest}.
 */
@DisplayName("topics rebuild service")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsRebuildServiceTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final TopicsTestData.Traces TRACES = new TopicsTestData.Traces(FIXTURE.traceIds,
            FIXTURE.createdAt, FIXTURE.intents, FIXTURE.vectors, FIXTURE.night);
    /** The fixture's reference grouping has 15 groups, so its recorded answer names the groups this run makes. */
    private static final TopicAlgorithm FIXTURE_ALGORITHM = TopicsTestAlgorithm.algorithm(
            TopicsTestAlgorithm.fixedGroups(15));
    private static final LocalDate NIGHT = LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, zookeeper, clickHouse).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysql);
        PostCutoverTraces.migrate(clickHouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                        new CustomConfig("inference.url", wireMock.server().baseUrl()),
                        new CustomConfig("inference.credential", "test-credential")))
                .build());
    }

    private TransactionTemplate template;
    private IdGenerator idGenerator;
    private TopicsTestData data;
    private TopicsConfig config;
    private TraceTopicsDAO traceTopicsDAO;
    private ProjectService projectService;
    private WorkspaceNameService workspaceNames;
    private TopicWindowDAO windowDAO;
    private InferenceClient client;
    private TopicRunStore store;
    private TopicsRebuildService service;
    private InMemoryMetricReader metricReader;

    private String workspaceId;
    private UUID projectId;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, IdGenerator idGenerator, ProjectService projectService,
            WorkspaceNameService workspaceNames, @Jit TopicVectorsDAO vectorsDAO, @Jit TopicWindowDAO windowDAO,
            @Jit TraceTopicsDAO traceTopicsDAO, @Jit InferenceClient client) {
        this.template = template;
        this.idGenerator = idGenerator;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, TRACES);
        this.config = opikConfig.getTopics();
        this.traceTopicsDAO = traceTopicsDAO;
        this.projectService = projectService;
        this.workspaceNames = workspaceNames;
        this.windowDAO = windowDAO;
        this.client = client;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouse.stop();
        zookeeper.stop();
        mysql.stop();
        network.close();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        // The app's beans with a meter of the test's own, so each test reads only what its run recorded.
        metricReader = InMemoryMetricReader.create();
        var metrics = metrics(metricReader);
        store = new TopicRunStore(config, template, traceTopicsDAO, projectService, idGenerator, metrics);
        service = new TopicsRebuildService(config, new TopicWindowReader(config, windowDAO),
                new TopicsLlm(config, client, metrics), store, idGenerator, metrics, workspaceNames,
                FIXTURE_ALGORITHM);
        workspaceId = idGenerator.generateId().toString();
        projectId = idGenerator.generateId();
        data.seed(workspaceId, projectId, 0, FIXTURE.size());
    }

    @Test
    @DisplayName("a saved run writes the named topics, the project's saved row and one trace_topics row per trace, "
            + "and counts the run, its rows and its Other share")
    void savedRun() {
        stubAnswer(FIXTURE.firstBuildAnswer);
        var runId = start();

        var outcome = service.firstBuild(runId, workspaceId, projectId, NIGHT, 250_000).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(outcome.stats().get("window_traces").asLong()).isEqualTo(250_000);
        assertThat(outcome.stats().get("sample_traces").asLong()).isEqualTo(FIXTURE.size());
        var topics = data.topics(projectId);
        var want = FIXTURE.expected.get("first_build");
        assertThat(topics).extracting(Topic::name, Topic::definition)
                .containsExactlyInAnyOrderElementsOf(expectedTopics(want));
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(runId);
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
            assertThat(project.listRunId()).isEqualTo(runId);
            assertThat(project.windowUntil()).isEqualTo(FIXTURE.night);
        });
        var idByName = topics.stream().collect(Collectors.toMap(Topic::name, Topic::id));
        assertThat(data.traceTopics(workspaceId, projectId)).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrderElementsOf(expectedTraceTopics(runId, want, idByName));

        assertThat(sum(metricReader, "topics.runs", "status", "saved", "workspace_id", workspaceId)).isEqualTo(1);
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isEqualTo(FIXTURE.size());
        assertThat(sum(metricReader, "topics.save.rows", "table", "topics")).isEqualTo(topics.size());
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "outcome", "ok")).isEqualTo(1);
        assertThat(points(metricReader, "topics.run.other_share")).singleElement()
                .satisfies(point -> assertThat(point.value()).isEqualTo(outcome.stats().get("other_share")
                        .asDouble()));
    }

    @Test
    @DisplayName("an answer that cannot be read fails the run with llm_bad_answer and writes no topic and no label")
    void unreadableAnswer() {
        stubAnswer(InferenceJson.MAPPER.getNodeFactory().textNode("Sorry, I cannot name these clusters."));
        var runId = start();

        var outcome = service.firstBuild(runId, workspaceId, projectId, NIGHT, FIXTURE.size()).block();

        assertThat(outcome.errorCode()).isEqualTo("llm_bad_answer");
        assertFailedWithNothingSaved(runId, TopicProject.RunError.LLM_BAD_ANSWER);
        assertThat(points(metricReader, "topics.llm.calls")).singleElement().satisfies(point -> {
            assertThat(point.get("call")).isEqualTo("author");
            assertThat(point.get("outcome")).isEqualTo("bad_answer");
        });
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "llm_bad_answer"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an LLM call the service refuses fails the run with llm_error and writes nothing")
    void refusedCall() {
        wireMock.server().stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"quota_exceeded\",\"message\":\"over quota\",\"retryable\":true}}")));
        var runId = start();

        var outcome = service.firstBuild(runId, workspaceId, projectId, NIGHT, FIXTURE.size()).block();

        assertThat(outcome.errorCode()).isEqualTo("llm_error");
        assertFailedWithNothingSaved(runId, TopicProject.RunError.LLM_ERROR);
        assertThat(sum(metricReader, "topics.llm.calls", "outcome", "error")).isEqualTo(1);
    }

    @Test
    @DisplayName("a first save of a project that has a list fails with save and leaves the list as it was")
    void secondSaveIsConflict() {
        data.savedList(workspaceId, projectId, FIXTURE.night, 5);
        var before = data.topics(projectId);
        stubAnswer(FIXTURE.firstBuildAnswer);
        var runId = start();

        var outcome = service.firstBuild(runId, workspaceId, projectId, NIGHT, FIXTURE.size()).block();

        assertThat(outcome.errorCode()).isEqualTo("save");
        assertThat(data.topics(projectId)).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(before);
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(runId);
            assertThat(project.runErrorCode()).isEqualTo(TopicProject.RunError.SAVE);
        });
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
        assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "save")).isEqualTo(1);
    }

    @Test
    @DisplayName("a run whose project was deleted before its commit keeps no topics and inserts no trace_topics")
    void projectDeletedDuringTheRun() {
        stubAnswer(FIXTURE.firstBuildAnswer);
        var runId = start();
        template.inTransaction(WRITE, handle -> handle.execute("DELETE FROM projects WHERE id = ?",
                projectId.toString()));

        var outcome = service.firstBuild(runId, workspaceId, projectId, NIGHT, FIXTURE.size()).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "outcome", "ok")).isZero();
    }

    @Test
    @DisplayName("a start that finds an attempt left running past the lease records it abandoned and counts it once")
    void startRecordsAnAbandonedAttempt() {
        var first = start();

        var second = store.startRun(workspace(), projectId, TopicProject.RunMode.FIRST_BUILD,
                Instant.now().plusSeconds(1)).block();

        assertThat(second).isNotEqualTo(first);
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(second);
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.RUNNING);
        });
        assertThat(points(metricReader, "topics.runs")).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(1);
            assertThat(point.get("status")).isEqualTo("failed");
            assertThat(point.get("error_code")).isEqualTo("abandoned");
        });
    }

    @Test
    @DisplayName("failing a run that already ended changes nothing and is not counted again")
    void alreadyEndedIsNotCounted() {
        var runId = start();

        assertThat(store.fail(runId, workspace(), projectId, TopicProject.RunError.TIMEOUT).block()).isTrue();
        assertThat(store.fail(runId, workspace(), projectId, TopicProject.RunError.SAVE).block()).isFalse();

        assertThat(data.topicProject(projectId).runErrorCode()).isEqualTo(TopicProject.RunError.TIMEOUT);
        assertThat(sum(metricReader, "topics.runs", "status", "failed")).isEqualTo(1);
    }

    private UUID start() {
        return store.startRun(workspace(), projectId, TopicProject.RunMode.FIRST_BUILD, Instant.EPOCH).block();
    }

    /** The workspace as the run records it: no name service runs in the test app, so the name is the id. */
    private TopicsMetrics.Workspace workspace() {
        return new TopicsMetrics.Workspace(workspaceId, workspaceId);
    }

    private void stubAnswer(JsonNode output) {
        wireMock.server().stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(answer(output, 0)));
    }

    private void assertFailedWithNothingSaved(UUID runId, TopicProject.RunError code) {
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(runId);
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.runErrorCode()).isEqualTo(code);
            assertThat(project.listRunId()).isNull();
        });
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
    }

    /** The fixture's first build topics, as name and definition. */
    private static List<Tuple> expectedTopics(JsonNode want) {
        var topics = want.get("topics");
        return IntStream.range(0, topics.size())
                .mapToObj(i -> tuple(topics.get(i).get("name").asText(), topics.get(i).get("definition").asText()))
                .toList();
    }

    /** A run row per trace, with the topic of the fixture's first build label, or Other. */
    private List<TraceTopic> expectedTraceTopics(UUID runId, JsonNode want, Map<String, UUID> idByName) {
        var labels = want.get("labels");
        var topics = want.get("topics");
        return IntStream.range(0, FIXTURE.size())
                .mapToObj(i -> TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(FIXTURE.traceIds.get(i))
                        .traceCreatedAt(FIXTURE.createdAt.get(i))
                        .topicId(labels.get(i).asInt() < 0
                                ? TraceTopic.OTHER
                                : idByName.get(topics.get(labels.get(i).asInt()).get("name").asText()))
                        .source(TraceTopic.Source.RUN)
                        .runId(runId)
                        .build())
                .toList();
    }
}
