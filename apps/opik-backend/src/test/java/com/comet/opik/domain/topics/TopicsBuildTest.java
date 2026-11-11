package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.redis.testcontainers.RedisContainer;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * the fixture's), start a run and call {@link TopicsRebuildService#firstBuild}: no night, no lock and no opt-in, which
 * are the caller's.
 */
@DisplayName("topics first build")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsBuildTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int BUILT = 120;

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
    private TopicsRebuildService rebuildService;
    private TopicsTestData data;

    @BeforeAll
    void beforeAll(TransactionTemplate template, TransactionTemplateAsync clickHouseTemplate,
            @Jit TopicsRebuildService rebuildService, @Jit TopicVectorsDAO vectorsDAO) {
        this.template = template;
        this.rebuildService = rebuildService;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, new TopicsTestData.Traces(
                FIXTURE.traceIds, FIXTURE.createdAt, FIXTURE.intents, FIXTURE.vectors, FIXTURE.night));
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
    @DisplayName("builds the project's first list and saves the run, its topics, prototypes, counts, state and trace "
            + "topics")
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
        // Every Other group's centre is saved, the gated ones too.
        assertThat(prototypes.stream().filter(prototype -> prototype.topicId() == null))
                .hasSize(json(run.getFirst().stats()).path("other_groups").asInt());

        var traceTopics = data.traceTopics(workspaceId, built);
        assertThat(traceTopics).hasSize(BUILT);
        assertThat(traceTopics).extracting(TraceTopic::traceId)
                .containsExactlyInAnyOrderElementsOf(FIXTURE.traceIds.subList(0, BUILT));
        assertThat(traceTopics).allSatisfy(row -> {
            assertThat(row.topicId().equals(TraceTopic.OTHER) || topicIds.contains(row.topicId())).isTrue();
            assertThat(row.source()).isEqualTo(TraceTopic.Source.NIGHT);
            assertThat(row.runId()).isEqualTo(runId.toString());
        });
        // Each topic's size is its count of window traces.
        topics.forEach(topic -> assertThat(traceTopics.stream().filter(row -> row.topicId().equals(topic.id())))
                .hasSize(topic.size()));

        // The run's counts: each topic's labels and size, ranked 1..n by size, then Other; they add up to the window.
        var labelled = traceTopics.stream().collect(Collectors.groupingBy(TraceTopic::topicId, Collectors.counting()));
        var counts = data.counts(built);
        assertThat(counts).hasSize(topics.size() + 1).allSatisfy(count -> {
            assertThat(count.runId()).isEqualTo(runId);
            assertThat(count.workspaceId()).isEqualTo(workspaceId);
            assertThat(count.savedAt()).isEqualTo(run.getFirst().finishedAt());
            assertThat(count.traces()).isEqualTo(labelled.getOrDefault(count.topicId(), 0L));
        });
        assertThat(counts.stream().mapToLong(TopicRunCount::traces).sum()).isEqualTo(BUILT);
        var ranks = new ArrayList<Integer>(java.util.stream.IntStream.rangeClosed(1, topics.size()).boxed().toList());
        ranks.add(null);
        assertThat(counts).extracting(TopicRunCount::rank).containsExactlyElementsOf(ranks);
        assertThat(counts.getLast().topicId()).isEqualTo(TraceTopic.OTHER);
        assertThat(counts.subList(0, topics.size())).extracting(TopicRunCount::traces)
                .isSortedAccordingTo(Comparator.reverseOrder());
        topics.forEach(topic -> assertThat(counts).filteredOn(count -> count.topicId().equals(topic.id()))
                .singleElement().satisfies(count -> assertThat(count.traces()).isEqualTo(topic.size())));

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
            assertThat(body.path("max_tokens").asInt()).isEqualTo(TopicsRebuildService.QWEN_MAX_TOKENS);
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
        assertThat(data.counts(projectId)).isEmpty();
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
        assertThat(data.counts(projectId)).isEmpty();
        assertThat(data.state(projectId)).isNull();
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
    }

    /** Starts a first-build run of the project and builds it on this thread, as the night and a run by hand do. */
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
        assertThat(data.counts(projectId)).isEmpty();
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
}
