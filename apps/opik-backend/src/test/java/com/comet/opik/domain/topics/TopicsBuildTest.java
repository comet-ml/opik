package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TopicsTraces;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.ConnectionFactory;
import org.awaitility.Awaitility;
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

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.domain.topics.TopicsTestData.json;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One project's first build against real ClickHouse and MySQL, with opik-inference's {@code /v1/generate} stubbed in
 * WireMock. Tests seed {@code topic_vectors} with the fixture's traces (its texts, vectors and times, so the night is
 * the fixture's), start a run and call {@link TopicsRebuildService#firstBuild}: no job, no lock and no opt-in, which
 * are the caller's.
 */
@DisplayName("topics first build")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsBuildTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int BUILT = 120;
    /** The window sample's {@code topics.build.maxVectors}, set for its one run. */
    private static final int MAX_VECTORS = 100;
    /** A Sunday: the weekly window's last day is a Saturday. */
    private static final Instant WEEKLY_NIGHT = Instant.parse("2026-10-18T00:00:00Z");

    // The app needs Redis to start; a build takes no lock.
    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);
    private final WireMockServer wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, zookeeper, clickHouse).join();
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        try {
            PostCutoverTraces.migrate(clickHouse);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .runtimeInfo(new WireMockRuntimeInfo(wireMock))
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                        new CustomConfig("aiLabels.enabled", "true"),
                        new CustomConfig("aiLabels.inference.url", wireMock.baseUrl()),
                        new CustomConfig("aiLabels.inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("topics.enabled", "true"),
                        new CustomConfig("topics.vectors.tickInterval", "1h"),
                        new CustomConfig("topics.vectors.tickBudget", "30s")))
                .build());
    }

    private TransactionTemplate template;
    private OpikConfiguration opikConfig;
    private ConnectionFactory connectionFactory;
    private TopicsRebuildService rebuildService;
    private TopicsTestData data;
    private TopicsTestData.Traces weeklyWindow;
    private TopicsTestData weekly;

    @BeforeAll
    void beforeAll(TransactionTemplate template, TransactionTemplateAsync clickHouseTemplate,
            OpikConfiguration opikConfig, ConnectionFactory connectionFactory,
            @Jit TopicsRebuildService rebuildService, @Jit TopicVectorsDAO vectorsDAO) {
        this.template = template;
        this.opikConfig = opikConfig;
        this.connectionFactory = connectionFactory;
        this.rebuildService = rebuildService;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, new TopicsTestData.Traces(
                FIXTURE.traceIds, FIXTURE.createdAt, FIXTURE.intents, FIXTURE.vectors, FIXTURE.night));
        this.weeklyWindow = weeklyTraces();
        this.weekly = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, weeklyWindow);
    }

    @AfterAll
    void afterAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("builds the project's first list and saves the run, its topics, prototypes, state and trace topics")
    void firstBuild() {
        var workspaceId = UUID.randomUUID().toString();
        var built = UUID.randomUUID();
        data.seed(workspaceId, built, 0, BUILT);
        stubAnswer(FIXTURE.answer);

        var outcome = startAndBuild(workspaceId, built, night());

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        var run = data.runs(built);
        assertThat(run).singleElement().satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(TopicRun.Status.SAVED);
            assertThat(saved.mode()).isEqualTo(TopicRun.Mode.FIRST_BUILD);
            assertThat(saved.finishedAt()).isNotNull();
            var stats = json(saved.stats());
            assertThat(stats.path("vectors").asInt()).isEqualTo(BUILT);
            // The fixture's first 120 traces hold 91 unique texts: k = max(30, 91 / 30) = 30, round(250 / 30) = 8.
            assertThat(stats.path("unique_texts").asInt()).isEqualTo(91);
            assertThat(stats.path("k").asInt()).isEqualTo(30);
            assertThat(stats.path("author_examples").asInt()).isEqualTo(8);
            assertThat(stats.path("seed").asInt()).isZero();
            assertThat(stats.path("provisional").asBoolean()).isTrue();
            assertThat(stats.path("qwen_calls").asInt()).isEqualTo(1);
            assertThat(stats.path("task_version_shares").path("1").asDouble()).isEqualTo(1.0);
            assertThat(stats.path("seconds").has("kmeans")).isTrue();
        });
        var runId = run.getFirst().runId();
        assertThat(outcome.runId()).isEqualTo(runId);

        var topics = data.topics(built);
        assertThat(topics).isNotEmpty().hasSizeLessThanOrEqualTo(TopicsRebuildService.MAX_TOPICS);
        assertThat(topics).allSatisfy(topic -> {
            assertThat(topic.workspaceId()).isEqualTo(workspaceId);
            assertThat(topic.born()).isEqualTo(night());
            assertThat(topic.retiredAt()).isNull();
            assertThat(AuthorApplier.isCatchAllName(topic.name())).isFalse();
        });
        var topicIds = topics.stream().map(Topic::id).collect(Collectors.toSet());

        var prototypes = data.prototypes(built);
        assertThat(prototypes).extracting(TopicPrototype::position)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, prototypes.size()).boxed().toList());
        assertThat(prototypes).allSatisfy(prototype -> {
            assertThat(prototype.vector()).hasSize(TopicsRebuildService.DIMENSIONS * Float.BYTES);
            assertThat(prototype.topicId() == null || topicIds.contains(prototype.topicId())).isTrue();
        });
        // Every Other group's centre is saved but the ones the 0.95 gate left out.
        assertThat(prototypes.stream().filter(prototype -> prototype.topicId() == null))
                .hasSize(json(run.getFirst().stats()).path("other_groups").asInt()
                        - json(run.getFirst().stats()).path("gated_other").asInt());

        var traceTopics = data.traceTopics(workspaceId, built);
        assertThat(traceTopics).hasSize(BUILT);
        assertThat(traceTopics).extracting(TraceTopic::traceId)
                .containsExactlyInAnyOrderElementsOf(FIXTURE.traceIds.subList(0, BUILT));
        assertThat(traceTopics).allSatisfy(row -> {
            assertThat(row.topicId().equals(TraceTopic.OTHER) || topicIds.contains(row.topicId())).isTrue();
            assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
            assertThat(row.runId()).isEqualTo(runId.toString());
        });
        // Each topic's size is its count of window traces.
        topics.forEach(topic -> assertThat(traceTopics.stream().filter(row -> row.topicId().equals(topic.id())))
                .hasSize(topic.size()));

        assertThat(data.state(built)).satisfies(state -> {
            assertThat(state.rebuilds()).isEqualTo(1);
            assertThat(state.lastRunId()).isEqualTo(runId);
            assertThat(state.windowUntil()).isEqualTo(start().truncatedTo(java.time.temporal.ChronoUnit.DAYS));
        });

        var sent = wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)));
        assertThat(sent).singleElement().satisfies(request -> {
            var body = json(request.getBodyAsString());
            assertThat(body.path("org_id").asText()).isEqualTo(workspaceId);
            assertThat(body.path("lane").asText()).isEqualTo("batch");
            // One request id format for every LLM call of a run: <runId>:<tag>.
            assertThat(body.path("request_id").asText()).isEqualTo(runId + ":author");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(KMeansTopicAlgorithm.AUTHOR_MAX_TOKENS);
            assertThat(body.path("schema")).isEqualTo(TopicPrompts.AUTHOR_SCHEMA);
            // The service decodes the keys in schema order: the topics before Other.
            assertThat(request.getBodyAsString())
                    .contains("\"schema\":{\"type\":\"object\",\"properties\":{\"topics\":");
            assertThat(body.path("messages").get(0).path("content").asText()).contains("cluster 0: ", "% of traffic");
        });
    }

    @Test
    @DisplayName("Qwen failing three times fails the run with qwen_error and writes nothing")
    void qwenFails() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)));

        var outcome = startAndBuild(workspaceId, projectId, night());

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.FAILED);
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(3);
        assertFailedWithNothingWritten(workspaceId, projectId, "qwen_error");
    }

    @Test
    @DisplayName("an answer with 11 topics saves the 10 largest")
    void elevenTopics() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        var answer = InferenceJson.MAPPER.createObjectNode();
        var topics = answer.putArray("topics");
        var assignment = answer.putArray("assignment");
        for (int j = 0; j < 11; j++) {
            topics.addObject().put("name", "Topic " + j).put("definition", "Requests of kind " + j);
            assignment.addObject().put("cluster", j).put("topic", j);
        }
        stubAnswer(answer);

        var outcome = startAndBuild(workspaceId, projectId, night());

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        assertThat(data.topics(projectId)).hasSize(10);
        // The 11th topic's group and every group the answer left out are Other.
        var stats = json(data.runs(projectId).getFirst().stats());
        assertThat(stats.path("other_groups").asInt()).isEqualTo(stats.path("k").asInt() - 10);
    }

    @Test
    @DisplayName("an answer without a JSON object fails the run with qwen_bad_answer and writes nothing")
    void badAnswer() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(InferenceJson.MAPPER.getNodeFactory().textNode("Sorry, I cannot name these clusters."));

        var outcome = startAndBuild(workspaceId, projectId, night());

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.FAILED);
        assertFailedWithNothingWritten(workspaceId, projectId, "qwen_bad_answer");
    }

    @Test
    @DisplayName("an answer the service finds off-schema fails the run with qwen_bad_answer, without a retry")
    void schemaViolation() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"schema_violation\",\"message\":\"m\",\"retryable\":true}}")));

        var outcome = startAndBuild(workspaceId, projectId, night());

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.FAILED);
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1);
        assertFailedWithNothingWritten(workspaceId, projectId, "qwen_bad_answer");
    }

    @Test
    @DisplayName("a run whose project another run saved meanwhile fails with save and writes no labels")
    void savedMeanwhile() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        data.seed(workspaceId, projectId, 0, BUILT, today);
        stubAnswer(FIXTURE.answer, 3_000);

        var build = CompletableFuture.supplyAsync(
                () -> startAndBuild(workspaceId, projectId, LocalDate.ofInstant(today, ZoneOffset.UTC)));
        // While Qwen answers, another run saves the project.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1));
        var other = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertState(TopicProjectState.builder()
                    .projectId(projectId)
                    .workspaceId(workspaceId)
                    .rebuilds(1)
                    .firstBuiltAt(today)
                    .lastRunId(other)
                    .lastSuccessAt(today)
                    .windowUntil(today)
                    .build());
            return null;
        });

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.runs(projectId))
                .singleElement().satisfies(run -> {
                    assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
                    assertThat(run.errorCode()).isEqualTo("save");
                    assertThat(run.errorMessage()).contains("another run saved this project meanwhile");
                }));
        assertThat(build.join().errorCode()).isEqualTo("save");
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.state(projectId).lastRunId()).isEqualTo(other);
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
    }

    @Test
    @DisplayName("a project deleted during the run fails the save, and nothing of the run stays")
    void deletedDuringRun() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        data.seed(workspaceId, projectId, 0, BUILT, today);
        stubAnswer(FIXTURE.answer, 3_000);

        var build = CompletableFuture.supplyAsync(
                () -> startAndBuild(workspaceId, projectId, LocalDate.ofInstant(today, ZoneOffset.UTC)));
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1));
        template.inTransaction(WRITE,
                handle -> handle.execute("DELETE FROM projects WHERE id = ?", projectId.toString()));

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.runs(projectId))
                .singleElement().satisfies(run -> {
                    assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
                    assertThat(run.errorCode()).isEqualTo("save");
                    assertThat(run.errorMessage()).contains("the project was deleted during the run");
                }));
        assertThat(build.join().errorCode()).isEqualTo("save");
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.state(projectId)).isNull();
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
    }

    /**
     * The project's traces have a weekly pattern, and the window's last day alone holds more weekend traces than the
     * run reads. The newest traces would be all weekend; the sample holds both.
     */
    @Test
    @DisplayName("a window over maxVectors builds on an even sample that sees weekdays and weekends, then every trace "
            + "of the window gets a row of the run, and the run's counts are of the whole window, not of the sample")
    void sampleSeesTheWholeWindow() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var traces = weeklyWindow;
        int count = traces.traceIds().size();
        weekly.seed(workspaceId, projectId, 0, count);
        TopicsTraces.insert(connectionFactory, PostCutoverTraces.SHARD, traces.traceIds().stream()
                .map(traceId -> TopicsTraces.TraceRow.shown(workspaceId, projectId, traceId,
                        traces.createdAt().get(traces.traceIds().indexOf(traceId))))
                .toList());
        stubAnswer(oneTopic());

        var build = opikConfig.getTopics().getBuild();
        int maxVectors = build.getMaxVectors();
        build.setMaxVectors(MAX_VECTORS);
        TopicsRebuildService.Outcome outcome;
        try {
            outcome = startAndBuild(workspaceId, projectId, LocalDate.ofInstant(WEEKLY_NIGHT, ZoneOffset.UTC));
        } finally {
            build.setMaxVectors(maxVectors);
        }
        var runId = outcome.runId();

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        var stats = json(data.runs(projectId).getFirst().stats());
        assertThat(stats.path("window_traces").asInt()).isEqualTo(count);
        assertThat(stats.path("sample_traces").asInt()).isEqualTo(MAX_VECTORS);
        assertThat(stats.path("vectors").asInt()).isEqualTo(MAX_VECTORS);
        // The newest 100 traces are all the last Saturday's; the sample reaches the weekdays too.
        var prompt = wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH))).getFirst().getBodyAsString();
        assertThat(prompt).contains("weekday intent").contains("weekend intent");

        var topics = data.topics(projectId);
        assertThat(topics).hasSize(1);
        var rows = data.traceTopics(workspaceId, projectId);
        assertThat(rows).extracting(TraceTopic::traceId).containsExactlyInAnyOrderElementsOf(traces.traceIds());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.runId()).isEqualTo(runId.toString());
            assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
            // Every trace is near a centre of its kind, and every group is the one topic's.
            assertThat(row.topicId()).isEqualTo(topics.getFirst().id());
        });
        // The topic's size is the sample's; the run's counts are the window's, with Other's row at zero.
        assertThat(topics.getFirst().size()).isEqualTo(MAX_VECTORS);
        assertThat(data.runCounts(workspaceId, runId))
                .isEqualTo(Map.of(topics.getFirst().id(), (long) count, TraceTopic.OTHER, 0L));
    }

    /** Starts a first-build run of the project and builds it on this thread, as the first-build job and a run by hand do. */
    private TopicsRebuildService.Outcome startAndBuild(String workspaceId, UUID projectId, LocalDate night) {
        var runId = rebuildService.startRun(workspaceId, projectId, TopicRun.Mode.FIRST_BUILD);
        return rebuildService.firstBuild(runId, workspaceId, projectId, night);
    }

    private void assertFailedWithNothingWritten(String workspaceId, UUID projectId, String errorCode) {
        assertThat(data.runs(projectId)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
            assertThat(run.errorCode()).isEqualTo(errorCode);
            assertThat(run.errorMessage()).isNotBlank();
            assertThat(json(run.stats()).path("vectors").asInt()).isEqualTo(BUILT);
        });
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.prototypes(projectId)).isEmpty();
        assertThat(data.state(projectId)).isNull();
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
    }

    /** The night after the fixture's last trace: its window is the fixture's. */
    private static Instant start() {
        return FIXTURE.night.plus(Duration.ofHours(2));
    }

    private static LocalDate night() {
        return LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);
    }

    private void stubAnswer(JsonNode output) {
        stubAnswer(output, 0);
    }

    private void stubAnswer(JsonNode output, int delayMillis) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(output, delayMillis)));
    }

    /** One topic that takes every group. */
    private static JsonNode oneTopic() {
        var answer = InferenceJson.MAPPER.createObjectNode();
        answer.putArray("topics").addObject()
                .put("name", "Plan questions")
                .put("definition", "Questions about the user's plan");
        var assignment = answer.putArray("assignment");
        for (int cluster = 0; cluster < KMeansTopicAlgorithm.groups(MAX_VECTORS); cluster++) {
            assignment.addObject().put("cluster", cluster).put("topic", 0);
        }
        return answer;
    }

    /**
     * Ten traces on each day of a 30-day window and 110 on its last day. Weekday traces have
     * weekday texts and vectors near one direction, weekend traces weekend texts and vectors near another.
     */
    private static TopicsTestData.Traces weeklyTraces() {
        int windowDays = 30;
        int perDay = 10;
        int lastDay = MAX_VECTORS + 10;
        int dimensions = TopicVectorsTickService.EMBED_DIMENSIONS;
        var random = new Random(5);
        var ids = Generators.timeBasedEpochGenerator(random);
        var weekday = unit(random, dimensions);
        var weekend = unit(random, dimensions);
        var traceIds = new ArrayList<UUID>();
        var createdAt = new ArrayList<Instant>();
        var texts = new ArrayList<String>();
        var vectors = new ArrayList<float[]>();
        for (int day = windowDays; day >= 1; day--) {
            var start = WEEKLY_NIGHT.minus(Duration.ofDays(day));
            var dayOfWeek = LocalDate.ofInstant(start, ZoneOffset.UTC).getDayOfWeek();
            boolean isWeekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
            int traces = day == 1 ? lastDay : perDay;
            for (int i = 0; i < traces; i++) {
                var created = start.plus(Duration.ofDays(1).multipliedBy(i).dividedBy(traces));
                createdAt.add(created);
                traceIds.add(ids.construct(created.toEpochMilli()));
                texts.add((isWeekend ? "weekend intent " : "weekday intent ") + texts.size());
                vectors.add(nudge(isWeekend ? weekend : weekday, random));
            }
        }
        var flat = new float[vectors.size() * dimensions];
        for (int i = 0; i < vectors.size(); i++) {
            System.arraycopy(vectors.get(i), 0, flat, i * dimensions, dimensions);
        }
        return new TopicsTestData.Traces(List.copyOf(traceIds), List.copyOf(createdAt),
                texts.toArray(String[]::new), flat, WEEKLY_NIGHT);
    }

    private static float[] unit(Random random, int dimensions) {
        var vector = new float[dimensions];
        for (int t = 0; t < dimensions; t++) {
            vector[t] = (float) random.nextGaussian();
        }
        return normalize(vector);
    }

    /** A unit vector at cosine about 0.95 from {@code base}. */
    private static float[] nudge(float[] base, Random random) {
        var noise = unit(random, base.length);
        var vector = new float[base.length];
        for (int t = 0; t < base.length; t++) {
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
