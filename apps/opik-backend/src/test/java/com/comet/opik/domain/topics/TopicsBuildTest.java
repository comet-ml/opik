package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.domain.topics.TopicProject.RunError;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * the fixture's), start a run and call {@link TopicsRebuildService#firstBuild}: no job, no lock and no opt-in, which
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
        PostCutoverTraces.migrate(clickHouse);

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
    private TopicTasks tasks;
    private TopicsTestData data;

    @BeforeAll
    void beforeAll(TransactionTemplate template, TransactionTemplateAsync clickHouseTemplate,
            @Jit TopicsRebuildService rebuildService, @Jit TopicVectorsDAO vectorsDAO, @Jit TopicTasks tasks) {
        this.template = template;
        this.rebuildService = rebuildService;
        this.tasks = tasks;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, new TopicsTestData.Traces(
                FIXTURE.traceIds, FIXTURE.createdAt, FIXTURE.intents, FIXTURE.vectors, FIXTURE.night));
    }

    @AfterAll
    void afterAll() {
        wireMock.stop();
        clickHouse.stop();
        zookeeper.stop();
        mysql.stop();
        network.close();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("builds the project's first list and saves its topics with their prototypes, the project's row and the "
            + "trace topics")
    void firstBuild() {
        var workspaceId = UUID.randomUUID().toString();
        var built = UUID.randomUUID();
        data.seed(workspaceId, built, 0, BUILT);
        stubAnswer(FIXTURE.answer);

        var outcome = startAndBuild(workspaceId, built, night());

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        var stats = outcome.stats();
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
        var runId = outcome.runId();

        var topics = data.topics(built);
        assertThat(topics).isNotEmpty().hasSizeLessThanOrEqualTo(TopicsRebuildService.MAX_TOPICS);
        assertThat(topics).allSatisfy(topic -> {
            assertThat(topic.workspaceId()).isEqualTo(workspaceId);
            assertThat(topic.born()).isEqualTo(night());
            // Every topic owns at least one prototype, each a vector of the embedding's size.
            assertThat(topic.vectors().vectors()).isNotEmpty()
                    .allSatisfy(vector -> assertThat(vector).hasSize(TopicsRebuildService.DIMENSIONS));
        });
        var topicIds = topics.stream().map(Topic::id).collect(Collectors.toSet());

        var traceTopics = data.traceTopics(workspaceId, built);
        assertThat(traceTopics).hasSize(BUILT);
        assertThat(traceTopics).extracting(TraceTopic::traceId)
                .containsExactlyInAnyOrderElementsOf(FIXTURE.traceIds.subList(0, BUILT));
        assertThat(traceTopics).allSatisfy(row -> {
            assertThat(row.topicId().equals(TraceTopic.OTHER) || topicIds.contains(row.topicId())).isTrue();
            assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
            assertThat(row.runId()).isEqualTo(runId);
        });
        // Each topic's size is its count of window traces.
        topics.forEach(topic -> assertThat(traceTopics.stream().filter(row -> row.topicId().equals(topic.id())))
                .hasSize(topic.size()));

        assertThat(data.topicProject(built)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(runId);
            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.FIRST_BUILD);
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
            assertThat(project.runFinishedAt()).isNotNull();
            assertThat(project.savedRuns()).isEqualTo(1);
            assertThat(project.failedRuns()).isZero();
            assertThat(project.listRunId()).isEqualTo(runId);
            assertThat(project.embedModel()).isEqualTo(TopicEmbedding.MODEL);
            assertThat(project.windowUntil()).isEqualTo(start().truncatedTo(java.time.temporal.ChronoUnit.DAYS));
            // Every Other group's centre is saved but the ones the 0.95 gate left out.
            int otherVectors = stats.path("other_groups").asInt() - stats.path("gated_other").asInt();
            assertThat(project.otherVectors() == null ? 0 : project.otherVectors().size()).isEqualTo(otherVectors);
        });

        var sent = wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)));
        assertThat(sent).singleElement().satisfies(request -> {
            var body = json(request.getBodyAsString());
            assertThat(body.path("org_id").asText()).isEqualTo(workspaceId);
            assertThat(body.path("lane").asText()).isEqualTo("batch");
            // One request id format for every LLM call of a run: <runId>:<tag>.
            assertThat(body.path("request_id").asText()).isEqualTo(runId + ":author");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(tasks.author().effectiveMaxTokens());
            assertThat(body.path("schema")).isEqualTo(tasks.author().schema());
            // The service decodes the keys in schema order: the topics before Other.
            assertThat(request.getBodyAsString())
                    .contains("\"schema\":{\"type\":\"object\",\"properties\":{\"topics\":");
            assertThat(body.path("messages").get(0).path("content").asText()).contains("cluster 0: ", "% of traffic");
        });
    }

    @Test
    @DisplayName("Qwen failing three times fails the run with llm_error and writes nothing")
    void qwenFails() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)));

        var outcome = startAndBuild(workspaceId, projectId, night());

        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(3);
        assertFailedWithNothingWritten(outcome, workspaceId, projectId, RunError.LLM_ERROR);
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

        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topics(projectId)).hasSize(10);
        // The 11th topic's group and every group the answer left out are Other.
        var stats = outcome.stats();
        assertThat(stats.path("other_groups").asInt()).isEqualTo(stats.path("k").asInt() - 10);
    }

    @Test
    @DisplayName("an answer without a JSON object fails the run with llm_bad_answer and writes nothing")
    void badAnswer() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(InferenceJson.MAPPER.getNodeFactory().textNode("Sorry, I cannot name these clusters."));

        var outcome = startAndBuild(workspaceId, projectId, night());

        assertFailedWithNothingWritten(outcome, workspaceId, projectId, RunError.LLM_BAD_ANSWER);
    }

    @Test
    @DisplayName("an answer the service finds off-schema fails the run with llm_bad_answer, without a retry")
    void schemaViolation() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"schema_violation\",\"message\":\"m\",\"retryable\":true}}")));

        var outcome = startAndBuild(workspaceId, projectId, night());

        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1);
        assertFailedWithNothingWritten(outcome, workspaceId, projectId, RunError.LLM_BAD_ANSWER);
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
        // While Qwen answers, another run saves the project's list.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1));
        var other = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                .saveList(workspaceId, projectId, null, other, today, today, null, TopicEmbedding.MODEL));

        assertThat(build.join().errorCode()).isEqualTo(RunError.SAVE.getValue());
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.runErrorCode()).isEqualTo(RunError.SAVE);
            assertThat(project.listRunId()).isEqualTo(other);
        });
        assertThat(data.topics(projectId)).isEmpty();
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

        assertThat(build.join().errorCode()).isEqualTo(RunError.SAVE.getValue());
        // The project's row goes with the ProjectsDeleted listener, which a direct delete does not fire.
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.runErrorCode()).isEqualTo(RunError.SAVE);
            assertThat(project.listRunId()).isNull();
        });
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();
    }

    /** Starts a first-build run of the project and builds it on this thread, as the first-build job and a run by hand do. */
    private TopicsRebuildService.Outcome startAndBuild(String workspaceId, UUID projectId, LocalDate night) {
        var runId = rebuildService.startRun(workspaceId, projectId, TopicProject.RunMode.FIRST_BUILD,
                Instant.now().minus(Duration.ofHours(1)));
        return rebuildService.firstBuild(runId, workspaceId, projectId, night);
    }

    /** The run failed with {@code code}: its attempt is counted on the project's row, and no list is saved. */
    private void assertFailedWithNothingWritten(TopicsRebuildService.Outcome outcome, String workspaceId,
            UUID projectId, RunError code) {
        assertThat(outcome.status()).isEqualTo(TopicProject.RunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(code.getValue());
        assertThat(outcome.stats().path("vectors").asInt()).isEqualTo(BUILT);
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(outcome.runId());
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.runErrorCode()).isEqualTo(code);
            assertThat(project.failedRuns()).isEqualTo(1);
            assertThat(project.firstFailedAt()).isEqualTo(project.runStartedAt());
            assertThat(project.savedRuns()).isZero();
            assertThat(project.listRunId()).isNull();
        });
        assertThat(data.topics(projectId)).isEmpty();
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
