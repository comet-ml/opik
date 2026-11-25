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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.redis.testcontainers.RedisContainer;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsMetricsReader.metrics;
import static com.comet.opik.domain.topics.TopicsMetricsReader.points;
import static com.comet.opik.domain.topics.TopicsMetricsReader.sum;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.domain.topics.TopicsTestData.answer;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

/**
 * The run service end to end: the window read from ClickHouse, the save to MySQL, the {@code trace_topics} insert
 * and opik-inference stubbed in WireMock. Each test has its own workspace and project, and some bind an algorithm of
 * their own. Failures that need a store to fail on cue are in {@link TopicsRunServiceFaultsTest}.
 */
@DisplayName("topics run service")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsRunServiceTest {

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
    private TopicWindowDAO windowDAO;
    private InferenceClient client;
    private TransactionTemplateAsync clickHouseTemplate;
    private TopicVectorsDAO vectorsDAO;
    private TopicsMetrics metrics;
    private TopicRunStore store;
    private TopicsRunService service;
    private InMemoryMetricReader metricReader;

    private String workspaceId;
    private UUID projectId;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, IdGenerator idGenerator, ProjectService projectService,
            @Jit TopicVectorsDAO vectorsDAO, @Jit TopicWindowDAO windowDAO,
            @Jit TraceTopicsDAO traceTopicsDAO, @Jit InferenceClient client) {
        this.template = template;
        this.idGenerator = idGenerator;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, TRACES);
        this.clickHouseTemplate = clickHouseTemplate;
        this.vectorsDAO = vectorsDAO;
        this.config = opikConfig.getTopics();
        this.traceTopicsDAO = traceTopicsDAO;
        this.projectService = projectService;
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
        metrics = metrics(metricReader);
        store = new TopicRunStore(config, template, traceTopicsDAO, projectService, idGenerator, metrics,
                mock(WorkspaceNameService.class));
        service = serviceWith(FIXTURE_ALGORITHM);
        workspaceId = idGenerator.generateId().toString();
        projectId = idGenerator.generateId();
        data.seed(workspaceId, projectId, 0, FIXTURE.size());
    }

    @Test
    @DisplayName("a saved run writes the named topics, the project's saved row and one trace_topics row per trace, "
            + "and counts the run, its rows and its Other share")
    void savedRun() {
        stubAnswer(FIXTURE.discoveryAnswer);
        var runId = start();

        var outcome = service.discover(runId, projectId, NIGHT, 250_000)
                .contextWrite(TopicsTestData.context(workspace())).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(outcome.stats().get("window_traces").asLong()).isEqualTo(250_000);
        assertThat(outcome.stats().get("sample_traces").asLong()).isEqualTo(FIXTURE.size());
        var topics = data.topics(projectId);
        var want = FIXTURE.expected.get("discovery");
        assertThat(topics).extracting(Topic::name, Topic::definition)
                .containsExactlyInAnyOrderElementsOf(expectedTopics(want));
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(runId);
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
            assertThat(project.listRunId()).isEqualTo(runId);
            assertThat(project.windowUntil()).isEqualTo(FIXTURE.night);
        });
        var idByName = topics.stream().collect(Collectors.toMap(Topic::name, Topic::id));
        assertThat(data.traceTopics(workspaceId, projectId))
                .containsExactlyInAnyOrderElementsOf(expectedTraceTopics(runId, want, idByName));

        assertThat(sum(metricReader, "topics.runs", "status", "saved", "workspace_id", workspaceId)).isEqualTo(1);
        assertThat(sum(metricReader, "topics.save.rows", "table", "trace_topics")).isEqualTo(FIXTURE.size());
        assertThat(sum(metricReader, "topics.save.rows", "table", "topics")).isEqualTo(topics.size());
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "result", "success")).isEqualTo(1);
        assertThat(points(metricReader, "topics.run.other_share")).singleElement()
                .satisfies(point -> assertThat(point.value()).isEqualTo(outcome.stats().get("other_share")
                        .asDouble()));
    }

    @Test
    @DisplayName("an answer that cannot be read fails the run with llm_bad_answer and writes no topic and no label")
    void unreadableAnswer() {
        stubAnswer(InferenceJson.MAPPER.getNodeFactory().textNode("Sorry, I cannot name these clusters."));
        var runId = start();

        var outcome = service.discover(runId, projectId, NIGHT, FIXTURE.size())
                .contextWrite(TopicsTestData.context(workspace())).block();

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

        var outcome = service.discover(runId, projectId, NIGHT, FIXTURE.size())
                .contextWrite(TopicsTestData.context(workspace())).block();

        assertThat(outcome.errorCode()).isEqualTo("llm_error");
        assertFailedWithNothingSaved(runId, TopicProject.RunError.LLM_ERROR);
        assertThat(sum(metricReader, "topics.llm.calls", "outcome", "error")).isEqualTo(1);
    }

    @Test
    @DisplayName("a window read that trips the ClickHouse row guard fails the run with window_read, before the LLM "
            + "call and the save")
    void windowReadGuardHit() {
        var read = config.getRun().getRead();
        long maxRows = read.getMaxRowsToRead();
        read.setMaxRowsToRead(1);
        try {
            var runId = start();

            var outcome = service.discover(runId, projectId, NIGHT, FIXTURE.size())
                    .contextWrite(TopicsTestData.context(workspace())).block();

            assertThat(outcome.errorCode()).isEqualTo("window_read");
            assertFailedWithNothingSaved(runId, TopicProject.RunError.WINDOW_READ);
            wireMock.server().verify(0, postRequestedFor(urlEqualTo(GENERATE_PATH)));
            assertThat(sum(metricReader, "topics.runs", "status", "failed", "error_code", "window_read"))
                    .isEqualTo(1);
        } finally {
            read.setMaxRowsToRead(maxRows);
        }
    }

    @Test
    @DisplayName("a discovery run whose project got a list while it ran fails its save with save, and leaves the "
            + "list as it was")
    void secondSaveIsConflict() {
        stubAnswer(FIXTURE.discoveryAnswer);
        var runId = start();
        // The queue refuses a discovery run on a project with a list: here the list came after the claim.
        template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class).saveList(workspaceId, projectId,
                null, TopicsTestData.newId(), Instant.now(), FIXTURE.night,
                new PrototypeVectors(List.of(TopicsTestData.unit(1))), TopicEmbedding.MODEL));
        var before = data.topics(projectId);

        var outcome = service.discover(runId, projectId, NIGHT, FIXTURE.size())
                .contextWrite(TopicsTestData.context(workspace())).block();

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
        stubAnswer(FIXTURE.discoveryAnswer);
        var runId = start();
        template.inTransaction(WRITE, handle -> handle.execute("DELETE FROM projects WHERE id = ?",
                projectId.toString()));

        var outcome = service.discover(runId, projectId, NIGHT, FIXTURE.size())
                .contextWrite(TopicsTestData.context(workspace())).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
        assertThat(sum(metricReader, "topics.trace_topics.inserts", "result", "success")).isZero();
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

    /**
     * Four texts, three traces each, so each text is its own group and its vector is the group's centre. The answer
     * names the topic for the group of {@code alpha}: {@code beta} and {@code gamma} are Other groups within 0.95 of it
     * (gated), {@code delta} is an Other group far from it (kept).
     */
    @Test
    @DisplayName("the gated Other prototypes are not saved, and the saved rows assign as the run did")
    void gatedOtherIsNotSaved() {
        int d = TopicEmbedding.DIMENSIONS;
        var alpha = unit(d, 0, 1, 0, 0);
        var gated = List.of(unit(d, 0, 0.97, 1, Math.sqrt(1 - 0.97 * 0.97)),
                unit(d, 0, 0.96, 2, Math.sqrt(1 - 0.96 * 0.96)));
        var delta = unit(d, 3, 1, 0, 0);
        var groupTexts = List.of("alpha request", "beta request", "gamma request", "delta request");
        var groupVectors = List.of(alpha, gated.getFirst(), gated.get(1), delta);
        int count = 12;
        var traceIds = new ArrayList<UUID>(count);
        var createdAt = new ArrayList<Instant>(count);
        var texts = new String[count];
        var vectors = new float[count * d];
        for (int i = 0; i < count; i++) {
            createdAt.add(FIXTURE.night.minusSeconds(3_600L * (count - i)));
            traceIds.add(TopicsTestData.newId());
            texts[i] = groupTexts.get(i % 4);
            System.arraycopy(groupVectors.get(i % 4), 0, vectors, i * d, d);
        }
        workspaceId = idGenerator.generateId().toString();
        projectId = idGenerator.generateId();
        new TopicsTestData(template, clickHouseTemplate, vectorsDAO,
                new TopicsTestData.Traces(traceIds, createdAt, texts, vectors, FIXTURE.night))
                .seed(workspaceId, projectId, 0, count);
        // The groups' numbers in the prompt are the algorithm's: each stub answers for one number.
        for (int group = 0; group <= groupTexts.size(); group++) {
            var output = InferenceJson.MAPPER.createObjectNode();
            output.putArray("topics").addObject().put("name", "Alpha Requests").put("definition", "d");
            output.putArray("assignment").addObject().put("cluster", group).put("topic", 0);
            wireMock.server().stubFor(post(urlEqualTo(GENERATE_PATH))
                    .withRequestBody(matching("(?s).*cluster " + group
                            + ": \\d+% of traffic\\\\n    - alpha request.*"))
                    .willReturn(answer(output, 0)));
        }
        var runId = start();

        var outcome = serviceWith(TopicsTestAlgorithm.algorithm())
                .discover(runId, projectId, NIGHT, count).contextWrite(TopicsTestData.context(workspace())).block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(outcome.stats().get("other_groups").asInt()).isEqualTo(3);
        assertThat(outcome.stats().get("gated_other").asInt()).isEqualTo(2);
        var topicRows = data.topics(projectId).stream().flatMap(topic -> topic.vectors().vectors().stream())
                .toList();
        var otherRows = data.topicProject(projectId).otherVectors().vectors();
        assertThat(topicRows).hasSize(1);
        assertThat(otherRows).singleElement().satisfies(other -> assertThat(other).containsExactly(delta));
        var algorithmConfig = TopicsTestAlgorithm.config();
        for (var other : otherRows) {
            for (var topic : topicRows) {
                assertThat(dot(other, topic)).isLessThan(algorithmConfig.getOtherGate());
            }
        }

        // The saved rows, with or without the gated twins, give the labels the run saved.
        var topicIdByTrace = data.traceTopics(workspaceId, projectId).stream()
                .collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId));
        var savedLabels = traceIds.stream()
                .mapToInt(traceId -> TraceTopic.OTHER.equals(topicIdByTrace.get(traceId)) ? TopicAlgorithm.OTHER : 0)
                .toArray();
        var withoutGated = new PrototypeAssigner(topicRows, List.of(0), otherRows, d,
                algorithmConfig.getAssignMinCosine(), algorithmConfig.getOtherGate());
        var withGated = new PrototypeAssigner(topicRows, List.of(0),
                Stream.concat(otherRows.stream(), gated.stream()).toList(), d,
                algorithmConfig.getAssignMinCosine(), algorithmConfig.getOtherGate());
        assertThat(withoutGated.assign(vectors)).containsExactly(savedLabels);
        assertThat(withGated.assign(vectors)).containsExactly(savedLabels);
        assertThat(savedLabels).containsExactly(0, 0, 0, TopicAlgorithm.OTHER, 0, 0, 0, TopicAlgorithm.OTHER, 0,
                0, 0, TopicAlgorithm.OTHER);
    }

    @Test
    @DisplayName("the service saves what the bound algorithm returns: its topics with their prototypes, labels and "
            + "stats")
    void savesWhatTheAlgorithmReturns() {
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
                    public DiscoveredTopics finish(AuthorAnswer answer) {
                        var labels = new int[window.size()];
                        for (int i = 0; i < labels.length; i++) {
                            labels[i] = i % 3 == 2 ? OTHER : i % 3;
                        }
                        return DiscoveredTopics.builder()
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
        stubAnswer(FIXTURE.discoveryAnswer);
        var runId = start();

        var outcome = serviceWith(stub).discover(runId, projectId, NIGHT, FIXTURE.size())
                .contextWrite(TopicsTestData.context(workspace()))
                .block();

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        var saved = data.topics(projectId);
        assertThat(saved).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id").containsExactlyInAnyOrder(
                topic("First", "one", first, 0), topic("Second", "two", second, 1));
        assertThat(data.topicProject(projectId).otherVectors()).isEqualTo(new PrototypeVectors(List.of(other)));
        var idByName = saved.stream().collect(Collectors.toMap(Topic::name, Topic::id));
        var topicIds = List.of(idByName.get("First"), idByName.get("Second"), TraceTopic.OTHER);
        assertThat(data.traceTopics(workspaceId, projectId))
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, FIXTURE.size())
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
        assertThat(json.get("stub_stat").asInt()).isEqualTo(config.getAlgorithm().getMaxTopics());
        assertThat(json.get("topics").asInt()).isEqualTo(2);
        assertThat(json.get("other_share").asDouble())
                .isEqualTo((double) IntStream.range(0, FIXTURE.size()).filter(i -> i % 3 == 2).count()
                        / FIXTURE.size());
    }

    private TopicsRunService serviceWith(TopicAlgorithm algorithm) {
        return new TopicsRunService(config, new TopicWindowReader(config, windowDAO),
                new TopicsLlm(config, client, metrics), store, new TopicRunOutcomes(store), idGenerator, metrics,
                algorithm);
    }

    /** A topic born on the run's night, with the traces whose index is {@code label} mod 3, and one prototype. */
    private Topic topic(String name, String definition, float[] prototype, int label) {
        return Topic.builder()
                .id(TraceTopic.OTHER)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name(name)
                .definition(definition)
                .born(NIGHT)
                .size((int) IntStream.range(0, FIXTURE.size()).filter(i -> i % 3 == label).count())
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

    /** Queues and claims a discovery run, as the planner and a pod's subscriber do. */
    private UUID start() {
        var runId = store.queue(workspaceId, projectId, TopicProject.RunMode.DISCOVERY).block();
        var message = TopicsRunMessage.builder().runId(runId).workspaceId(workspaceId).projectId(projectId)
                .mode(TopicProject.RunMode.DISCOVERY).night(NIGHT).windowTraces(FIXTURE.size()).build();
        assertThat(store.claim(message, Instant.now().plusSeconds(3600)).block()).isTrue();
        return runId;
    }

    /** The workspace as the entry point hands it in; the test names it by its id. */
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

    /** The fixture's discovery run topics, as name and definition. */
    private static List<Tuple> expectedTopics(JsonNode want) {
        var topics = want.get("topics");
        return IntStream.range(0, topics.size())
                .mapToObj(i -> tuple(topics.get(i).get("name").asText(), topics.get(i).get("definition").asText()))
                .toList();
    }

    /** A run row per trace, with the topic of the fixture's discovery run label, or Other. */
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

    /**
     * A nightly update driven by the service, with the k-means algorithm and opik-inference in WireMock: two live
     * topics, two tight groups no topic took, and a naming that assigns both to one topic. It makes the naming call,
     * the verify of the two assigns and the second naming of the groups the verify rejected.
     */
    @Nested
    @DisplayName("nightly update")
    class Night {

        private static final float[] EAST = {1, 0};
        private static final float[] NORTH = {0, 1};
        private static final String NAMING = "{\"assign\": [{\"cluster\": 0, \"topic\": 2}, "
                + "{\"cluster\": 1, \"topic\": 2}], \"new\": [], \"other\": []}";
        private static final String NAMING_NEW = "{\"assign\": [], \"new\": [{\"name\": \"north\", "
                + "\"definition\": \"N.\", \"clusters\": [0, 1]}], \"other\": []}";

        private UUID runId;
        private TopicRun run;

        @BeforeEach
        void setUpRun() {
            runId = idGenerator.generateId();
            run = TopicRun.of(runId, workspaceId, workspaceId, projectId, NIGHT, config.getRun().getWindowDays());
            service = serviceWith(TopicsTestAlgorithm.algorithm());
        }

        @Test
        @DisplayName("each call is made in turn with its kind's deadline and its tag in the request id; an answer that "
                + "is not an object reaches the algorithm as null and counts bad_answer")
        void callsInTurn() {
            stubCall("naming", answer(json(NAMING), 0));
            stubCall("verify_naming", answer(InferenceJson.MAPPER.getNodeFactory().textNode("not an object"), 0));
            stubCall("naming_new", answer(json(NAMING_NEW), 0));
            var state = state();
            var started = Instant.now();

            var update = service.update(run, state, window()).block();
            var elapsed = Duration.between(started, Instant.now());

            var requests = requests();
            assertThat(requests).extracting(request -> request.get("request_id").asText())
                    .containsExactly(runId + ":naming", runId + ":verify_naming", runId + ":naming_new");
            assertThat(requests).extracting(request -> request.get("max_tokens").asInt())
                    .containsExactly(2_500, 110, 2_500);
            var naming = config.getRun().getNamingDeadline().toJavaDuration();
            var verify = config.getRun().getVerifyDeadline().toJavaDuration();
            deadline(requests.get(0), naming, elapsed);
            deadline(requests.get(1), verify, elapsed);
            deadline(requests.get(2), naming, elapsed);
            // The verify answer verified no pair; the second naming names both after a live topic: known Other.
            assertThat(update.stats().get("forced").get("other_name").asInt()).isEqualTo(2);
            var tasks = new TopicTasks();
            assertThat(update.stats().get("naming_task_version").asInt()).isEqualTo(tasks.naming().version());
            assertThat(update.stats().get("naming_new_task_version").asInt()).isEqualTo(tasks.namingNew().version());
            assertThat(update.stats().get("verify_task_version").asInt()).isEqualTo(tasks.verify().version());
            assertThat(update.stats().get("llm_calls").asInt()).isEqualTo(3);
            assertThat(update.stats().get("llm_input_tokens").asInt()).isEqualTo(3_000);
            assertThat(state.getTopics()).hasSize(2);
            assertThat(sum(metricReader, "topics.llm.calls", "call", "naming", "outcome", "ok")).isEqualTo(2);
            assertThat(sum(metricReader, "topics.llm.calls", "call", "verify", "outcome", "bad_answer")).isEqualTo(1);
            assertThat(points(metricReader, "topics.run.duration_seconds"))
                    .anyMatch(point -> "llm".equals(point.get("phase")));
        }

        @Test
        @DisplayName("a failed call errors with llm_error and the night asks nothing more")
        void failedCall() {
            stubCall("naming", answer(json(NAMING), 0));
            stubCall("verify_naming", aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":{\"code\":\"quota_exceeded\",\"message\":\"over quota\","
                            + "\"retryable\":true}}"));

            assertThatThrownBy(() -> service.update(run, state(), window()).block())
                    .isInstanceOfSatisfying(TopicsRunException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(TopicProject.RunError.LLM_ERROR));
            assertThat(requests()).extracting(request -> request.get("request_id").asText())
                    .doesNotContain(runId + ":naming_new");
            assertThat(sum(metricReader, "topics.llm.calls", "call", "verify", "outcome", "error")).isEqualTo(1);
        }

        @Test
        @DisplayName("a call past its deadline fails with llm_timeout")
        void timedOutCall() {
            var deadline = config.getRun().getNamingDeadline();
            config.getRun().setNamingDeadline(io.dropwizard.util.Duration.seconds(1));
            try {
                stubCall("naming", answer(json(NAMING), 3_000));

                assertThatThrownBy(() -> service.update(run, state(), window()).block())
                        .isInstanceOfSatisfying(TopicsRunException.class, exception -> assertThat(
                                exception.getCode()).isEqualTo(TopicProject.RunError.LLM_TIMEOUT));
                assertThat(sum(metricReader, "topics.llm.calls", "call", "naming", "outcome", "timeout"))
                        .isEqualTo(1);
            } finally {
                config.getRun().setNamingDeadline(deadline);
            }
        }

        private void stubCall(String tag, ResponseDefinitionBuilder response) {
            wireMock.server().stubFor(post(urlEqualTo(GENERATE_PATH))
                    .withRequestBody(matchingJsonPath("$.request_id", equalTo(runId + ":" + tag)))
                    .willReturn(response));
        }

        /** The generate requests WireMock got, in the order it got them. */
        private List<JsonNode> requests() {
            return wireMock.server().findAll(postRequestedFor(urlEqualTo(GENERATE_PATH))).stream()
                    .map(request -> json(request.getBodyAsString()))
                    .toList();
        }

        /** The request's deadline is its kind's, less at most the time the night took. */
        private static void deadline(JsonNode request, Duration expected, Duration elapsed) {
            assertThat(request.get("deadline_ms").asLong())
                    .isBetween(expected.minus(elapsed).toMillis(), expected.toMillis());
        }

        private static NightState state() {
            return NightState.builder()
                    .topics(List.of(topic("East", EAST), topic("North", NORTH)))
                    .otherPrototypes(List.of())
                    .rebuilds(1)
                    .night(NIGHT)
                    .ids(UUID::randomUUID)
                    .build();
        }

        private static NightTopic topic(String name, float[] prototype) {
            return NightTopic.builder()
                    .id(UUID.nameUUIDFromBytes(name.getBytes()))
                    .name(name)
                    .definition(name + " def")
                    .born(NIGHT.minusDays(20))
                    .prototypes(List.of(prototype))
                    .build();
        }

        /**
         * 1000 traces on each topic, then two small groups no topic takes: 35 near North, below the assign cosine,
         * and 30 opposite East.
         */
        private static NightWindow window() {
            var near = new float[]{0.68f, 0.7332f};
            var south = new float[]{0, -1};
            int n = 2_065;
            var texts = new String[n];
            var rows = new float[n * 2];
            var createdAt = new Instant[n];
            var night = NIGHT.atStartOfDay(ZoneOffset.UTC).toInstant();
            for (int i = 0; i < n; i++) {
                texts[i] = "t" + i;
                createdAt[i] = night.minus(Duration.ofDays(5));
                System.arraycopy(i < 1_000 ? EAST : i < 2_000 ? NORTH : i < 2_035 ? near : south, 0, rows, i * 2, 2);
            }
            return NightWindow.builder().texts(texts).vectors(rows).createdAt(createdAt)
                    .from(night.minus(Duration.ofDays(30))).night(night).dimensions(2).build();
        }

        private static JsonNode json(String text) {
            try {
                return InferenceJson.MAPPER.readTree(text);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
