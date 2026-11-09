package com.comet.opik.domain.topics;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.v1.events.TopicsProjectDeletedListener;
import com.comet.opik.api.resources.v1.jobs.TopicsFirstBuildJob;
import com.comet.opik.api.resources.v1.jobs.TopicsNightlyJob;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.lock.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The topics nightly job against real ClickHouse, MySQL and Redis, with opik-inference's {@code /v1/generate} stubbed
 * in WireMock. Tests seed {@code topic_vectors} with the fixture's traces (its texts, vectors and times, so the night
 * is the fixture's) and call {@link TopicsNightlyService#runNight(Instant)}; the scheduled job fires at 02:00 UTC only.
 * The first-build job's tests call {@link TopicsNightlyService#runFirstBuilds(Instant)}; the scheduled one fires at
 * startup, before any workspace is opted in, and then hourly.
 */
@DisplayName("topics nightly job")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsNightlyJobTest {

    private static final String GENERATE_PATH = "/v1/generate";
    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int BUILT = 120;
    private static final int SMALL = 50;

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
                        new CustomConfig("topics.vectors.tickBudget", "30s"),
                        new CustomConfig("topics.firstBuild.interval", "1h")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TransactionTemplateAsync clickHouseTemplate;
    private TopicsNightlyService nightlyService;
    private TopicVectorsDAO vectorsDAO;
    private TopicWindowDAO windowDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicsProjectDeletedListener projectDeletedListener;
    private GuiceJobManager jobManager;
    private LockService lockService;
    private ClientSupport client;
    private String baseUrl;

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, GuiceJobManager jobManager, LockService lockService,
            @Jit TopicsNightlyService nightlyService, @Jit TopicVectorsDAO vectorsDAO, @Jit TopicWindowDAO windowDAO,
            @Jit TraceTopicsDAO traceTopicsDAO, @Jit TopicsProjectDeletedListener projectDeletedListener) {
        this.client = client;
        this.opikConfig = opikConfig;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.template = template;
        this.clickHouseTemplate = clickHouseTemplate;
        this.jobManager = jobManager;
        this.lockService = lockService;
        this.nightlyService = nightlyService;
        this.vectorsDAO = vectorsDAO;
        this.windowDAO = windowDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.projectDeletedListener = projectDeletedListener;
    }

    @AfterAll
    void afterAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        // Each test builds its own workspaces: the earlier ones must not be eligible again.
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE");
            return null;
        });
        opikConfig.getTopics().setEnabledWorkspaces("");
        wireMock.resetAll();
    }

    @Test
    @DisplayName("enabled, the job is scheduled on the nightly cron")
    void jobIsScheduled() throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var jobKeys = new ArrayList<JobKey>();
        for (var key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            if (scheduler.getJobDetail(key).getJobClass() == TopicsNightlyJob.class) {
                jobKeys.add(key);
            }
        }

        assertThat(jobKeys).singleElement().satisfies(key -> assertThat(scheduler.getTriggersOfJob(key))
                .singleElement()
                .isInstanceOfSatisfying(CronTrigger.class,
                        trigger -> assertThat(trigger.getCronExpression()).isEqualTo("0 0 2 * * ?")));
    }

    @Test
    @DisplayName("enabled, the first-build job is scheduled on its interval")
    void firstBuildJobIsScheduled() throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var jobKeys = new ArrayList<JobKey>();
        for (var key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            if (scheduler.getJobDetail(key).getJobClass() == TopicsFirstBuildJob.class) {
                jobKeys.add(key);
            }
        }

        assertThat(jobKeys).singleElement().satisfies(key -> assertThat(scheduler.getTriggersOfJob(key))
                .singleElement()
                .isInstanceOfSatisfying(SimpleTrigger.class,
                        trigger -> assertThat(trigger.getRepeatInterval()).isEqualTo(Duration.ofHours(1).toMillis())));
    }

    @Test
    @DisplayName("builds only the enabled, existing project with minVectors, saves the run, and skips it the next night")
    void firstBuild() {
        var workspaceId = enabledWorkspace();
        var built = UUID.randomUUID();
        var small = UUID.randomUUID();
        var disabled = UUID.randomUUID();
        var deleted = UUID.randomUUID();
        seed(workspaceId, built, 0, BUILT);
        seed(workspaceId, small, BUILT, SMALL);
        seed(UUID.randomUUID().toString(), disabled, 0, BUILT);
        seed(workspaceId, deleted, 0, BUILT);
        template.inTransaction(WRITE,
                handle -> handle.execute("DELETE FROM projects WHERE id = ?", deleted.toString()));
        stubAnswer(FIXTURE.answer);

        var result = nightlyService.runNight(start());

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(runs(small)).isEmpty();
        assertThat(runs(disabled)).isEmpty();
        assertThat(runs(deleted)).isEmpty();

        var run = runs(built);
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

        // The night's record, which the state gauges read.
        template.inTransaction(READ_ONLY, handle -> {
            var nights = handle.attach(TopicNightDAO.class);
            assertThat(nights.findByNight(night()).outcome()).isEqualTo(TopicNight.Outcome.FINISHED);
            assertThat(nights.findProjects(night())).filteredOn(row -> workspaceId.equals(row.workspaceId()))
                    .extracting(TopicNightProject::projectId, TopicNightProject::decision, TopicNightProject::reason,
                            TopicNightProject::runId)
                    .containsExactlyInAnyOrder(
                            tuple(built, "first_build", null, runId),
                            tuple(small, "skip", "small", null),
                            tuple(deleted, "skip", "deleted", null));
            return null;
        });

        var topics = topics(built);
        assertThat(topics).isNotEmpty().hasSizeLessThanOrEqualTo(TopicsRebuildService.MAX_TOPICS);
        assertThat(topics).allSatisfy(topic -> {
            assertThat(topic.workspaceId()).isEqualTo(workspaceId);
            assertThat(topic.born()).isEqualTo(night());
            assertThat(topic.retiredAt()).isNull();
            assertThat(AuthorApplier.isCatchAllName(topic.name())).isFalse();
        });
        var topicIds = topics.stream().map(Topic::id).collect(Collectors.toSet());

        var prototypes = prototypes(built);
        assertThat(prototypes).extracting(TopicPrototype::position)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, prototypes.size()).boxed().toList());
        assertThat(prototypes).allSatisfy(prototype -> {
            assertThat(prototype.vector()).hasSize(TopicsRebuildService.DIMENSIONS * Float.BYTES);
            assertThat(prototype.topicId() == null || topicIds.contains(prototype.topicId())).isTrue();
        });
        // Every Other group's centre is saved, the gated ones too.
        assertThat(prototypes.stream().filter(prototype -> prototype.topicId() == null))
                .hasSize(json(run.getFirst().stats()).path("other_groups").asInt());

        var traceTopics = traceTopicsDAO.findByProject(workspaceId, built).collectList().block();
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
        var counts = counts(built);
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

        assertThat(state(built)).satisfies(state -> {
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

        // The next night: the project has a list, and stage 2a has no nightly update.
        var again = nightlyService.runNight(start().plus(Duration.ofDays(1)));

        assertThat(again.saved()).isZero();
        assertThat(again.skipped()).isGreaterThanOrEqualTo(1);
        assertThat(runs(built)).hasSize(1);
        assertThat(topics(built)).hasSameSizeAs(topics);
    }

    @Test
    @DisplayName("Qwen failing three times fails the run with qwen_error and writes nothing")
    void qwenFails() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)));

        var result = nightlyService.runNight(start());

        assertThat(result.failed()).isEqualTo(1);
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(3);
        assertFailedWithNothingWritten(workspaceId, projectId, "qwen_error");
    }

    @Test
    @DisplayName("an answer with 11 topics saves the 10 largest")
    void elevenTopics() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        var answer = InferenceJson.MAPPER.createObjectNode();
        var topics = answer.putArray("topics");
        var assignment = answer.putArray("assignment");
        for (int j = 0; j < 11; j++) {
            topics.addObject().put("name", "Topic " + j).put("definition", "Requests of kind " + j);
            assignment.addObject().put("cluster", j).put("topic", j);
        }
        stubAnswer(answer);

        var result = nightlyService.runNight(start());

        assertThat(result.saved()).isEqualTo(1);
        assertThat(topics(projectId)).hasSize(10);
        // The 11th topic's group and every group the answer left out are Other.
        var stats = json(runs(projectId).getFirst().stats());
        assertThat(stats.path("other_groups").asInt()).isEqualTo(stats.path("k").asInt() - 10);
    }

    @Test
    @DisplayName("an answer without a JSON object fails the run with qwen_bad_answer and writes nothing")
    void badAnswer() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(InferenceJson.MAPPER.getNodeFactory().textNode("Sorry, I cannot name these clusters."));

        var result = nightlyService.runNight(start());

        assertThat(result.failed()).isEqualTo(1);
        assertFailedWithNothingWritten(workspaceId, projectId, "qwen_bad_answer");
    }

    @Test
    @DisplayName("an answer the service finds off-schema fails the run with qwen_bad_answer, without a retry")
    void schemaViolation() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"schema_violation\",\"message\":\"m\",\"retryable\":true}}")));

        var result = nightlyService.runNight(start());

        assertThat(result.failed()).isEqualTo(1);
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1);
        assertFailedWithNothingWritten(workspaceId, projectId, "qwen_bad_answer");
    }

    @Test
    @DisplayName("a run whose project another run saved meanwhile fails with save and writes no labels")
    void savedMeanwhile() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        seed(workspaceId, projectId, 0, BUILT, FIXTURE.night, today);
        stubAnswer(FIXTURE.answer, 3_000);

        try (var response = rebuild(workspaceId, projectId)) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        // While Qwen answers, another run (its Redis lock lost) saves the project.
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

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(runs(projectId))
                .singleElement().satisfies(run -> {
                    assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
                    assertThat(run.errorCode()).isEqualTo("save");
                    assertThat(run.errorMessage()).contains("another run saved this project meanwhile");
                }));
        assertThat(topics(projectId)).isEmpty();
        assertThat(counts(projectId)).isEmpty();
        assertThat(state(projectId).lastRunId()).isEqualTo(other);
        assertThat(traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("a project deleted during the run fails the save, and nothing of the run stays")
    void deletedDuringRun() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        seed(workspaceId, projectId, 0, BUILT, FIXTURE.night, today);
        stubAnswer(FIXTURE.answer, 3_000);

        try (var response = rebuild(workspaceId, projectId)) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1));
        template.inTransaction(WRITE,
                handle -> handle.execute("DELETE FROM projects WHERE id = ?", projectId.toString()));

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(runs(projectId))
                .singleElement().satisfies(run -> {
                    assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
                    assertThat(run.errorCode()).isEqualTo("save");
                    assertThat(run.errorMessage()).contains("the project was deleted during the run");
                }));
        assertThat(topics(projectId)).isEmpty();
        assertThat(counts(projectId)).isEmpty();
        List<TopicProjectState> states = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findStates(Set.of(projectId)));
        assertThat(states).isEmpty();
        assertThat(traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("the trace_topics reads drop a trace whose latest row is a tombstone, and keep one whose tombstone is older")
    void traceTopicsReadsSkipTombstones() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var tombstoned = FIXTURE.traceIds.get(0);
        var revived = FIXTURE.traceIds.get(1);
        var untouched = FIXTURE.traceIds.get(2);
        var topicId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-11-01T10:00:00Z");
        var earlier = Instant.parse("2026-11-02T00:00:00Z");
        var later = Instant.parse("2026-11-03T00:00:00Z");
        // The tombstone comes after the live row, so the trace is gone.
        insertTraceTopic(workspaceId, projectId, tombstoned, createdAt, topicId, earlier, 0);
        insertTraceTopic(workspaceId, projectId, tombstoned, createdAt, topicId, later, 1);
        // The tombstone comes before the live row: the dedup keeps the live row, so the trace stays.
        insertTraceTopic(workspaceId, projectId, revived, createdAt, topicId, earlier, 1);
        insertTraceTopic(workspaceId, projectId, revived, createdAt, topicId, later, 0);
        insertTraceTopic(workspaceId, projectId, untouched, createdAt, TraceTopic.OTHER, earlier, 0);

        assertThat(traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block())
                .extracting(TraceTopic::traceId)
                .containsExactlyInAnyOrder(revived, untouched);
        assertThat(traceTopicsDAO.countByTopic(workspaceId, projectId, createdAt, createdAt.plusSeconds(1)).block())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(topicId, 1L, TraceTopic.OTHER, 1L));
    }

    /** One raw {@code trace_topics} row with its version and soft-delete marker, as no production write sets them. */
    private void insertTraceTopic(String workspaceId, UUID projectId, UUID traceId, Instant traceCreatedAt,
            UUID topicId, Instant lastUpdatedAt, int isDeleted) {
        clickHouseTemplate
                .nonTransaction(connection -> Mono
                        .from(connection
                                .createStatement(
                                        """
                                                INSERT INTO trace_topics (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id,
                                                    last_updated_at, is_deleted)
                                                VALUES (:workspace_id, :project_id, :trace_id, parseDateTime64BestEffort(:trace_created_at, 6),
                                                    :topic_id, 'night', :run_id, parseDateTime64BestEffort(:last_updated_at, 6), :is_deleted)
                                                """)
                                .bind("workspace_id", workspaceId)
                                .bind("project_id", projectId.toString())
                                .bind("trace_id", traceId.toString())
                                .bind("trace_created_at", traceCreatedAt.toString())
                                .bind("topic_id", topicId.toString())
                                .bind("run_id", UUID.randomUUID().toString())
                                .bind("last_updated_at", lastUpdatedAt.toString())
                                .bind("is_deleted", isDeleted)
                                .execute())
                        .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }

    @Test
    @DisplayName("deleting a project deletes its topics, prototypes, state, runs and run counts")
    void projectDeleted() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var kept = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        seed(workspaceId, kept, 0, BUILT);
        stubAnswer(FIXTURE.answer);
        assertThat(nightlyService.runNight(start()).saved()).isEqualTo(2);

        projectDeletedListener.onProjectsDeleted(new ProjectsDeleted(Set.of(projectId), workspaceId, "test"));

        assertThat(topics(projectId)).isEmpty();
        assertThat(prototypes(projectId)).isEmpty();
        assertThat(state(projectId)).isNull();
        assertThat(runs(projectId)).isEmpty();
        assertThat(counts(projectId)).isEmpty();
        assertThat(topics(kept)).isNotEmpty();
        assertThat(runs(kept)).hasSize(1);
        assertThat(counts(kept)).isNotEmpty();
    }

    @Test
    @DisplayName("the internal endpoint starts a first build now, and refuses a project that has a list")
    void rebuildByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        // The by-hand night is today: its window is the 30 days before today's start.
        var today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        seed(workspaceId, projectId, 0, BUILT, FIXTURE.night, today);
        stubAnswer(FIXTURE.answer);

        int runIdStatus;
        String runId;
        try (var response = rebuild(workspaceId, projectId)) {
            runIdStatus = response.getStatus();
            runId = response.readEntity(JsonNode.class).path("run_id").asText();
        }

        assertThat(runIdStatus).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(runs(projectId))
                .singleElement().satisfies(run -> {
                    assertThat(run.runId().toString()).isEqualTo(runId);
                    assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED);
                }));
        assertThat(topics(projectId)).isNotEmpty().allSatisfy(topic -> assertThat(topic.born())
                .isEqualTo(LocalDate.now(ZoneOffset.UTC)));
        try (var response = rebuild(workspaceId, projectId)) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = rebuild(workspaceId, UUID.randomUUID(), "yesterday")) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
        try (var response = rebuild(workspaceId, UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(2).toString())) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
        try (var response = rebuild(workspaceId, UUID.randomUUID(), "-999999999-01-05")) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("the internal endpoint builds the window of the night it is given")
    void rebuildByHandForANight() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(FIXTURE.answer);

        try (var response = rebuild(workspaceId, projectId, night().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(runs(projectId))
                .singleElement().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
        assertThat(topics(projectId)).isNotEmpty().allSatisfy(topic -> assertThat(topic.born()).isEqualTo(night()));
    }

    @Test
    @DisplayName("a workspace opted in to ai-labels but not to topics is neither built by the night nor by hand")
    void topicsOptIn() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(FIXTURE.answer);

        assertThat(nightlyService.runNight(start()).saved()).isZero();
        assertThat(runs(projectId)).isEmpty();
        try (var response = rebuild(workspaceId, projectId, night().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        assertThat(runs(projectId)).isEmpty();
    }

    @Test
    @DisplayName("by hand, a window under minVectors, or another workspace's project, is refused")
    void byHandFollowsTheNightsRules() {
        var workspaceId = enabledWorkspace();
        var small = UUID.randomUUID();
        seed(workspaceId, small, BUILT, SMALL);
        var foreign = UUID.randomUUID();
        seed(enabledWorkspace(), foreign, 0, BUILT);

        try (var response = rebuild(workspaceId, small, night().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = rebuild(workspaceId, foreign, night().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        assertThat(runs(small)).isEmpty();
        assertThat(runs(foreign)).isEmpty();
    }

    @Test
    @DisplayName("the night endpoint runs the whole night now and builds the eligible project")
    void runNightByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(FIXTURE.answer);

        try (var response = runNight(night().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.readEntity(JsonNode.class).path("night").asText()).isEqualTo(night().toString());
        }
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(runs(projectId))
                .singleElement().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
        // Released when the night ended: a second night can start.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (var response = runNight(night().toString())) {
                assertThat(response.getStatus()).isEqualTo(202);
            }
        });
        try (var response = runNight("tomorrow")) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("housekeeping leaves a run younger than a run can last: it may be a live run started by hand")
    void housekeepingSparesALiveRun() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder().runId(runId).workspaceId(workspaceId)
                    .projectId(projectId).mode(TopicRun.Mode.FIRST_BUILD).status(TopicRun.Status.RUNNING)
                    .startedAt(Instant.now().minus(Duration.ofMinutes(2))).build());
            return null;
        });

        nightlyService.runNight(Instant.now());

        assertThat(runs(projectId)).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.RUNNING));
    }

    @Test
    @DisplayName("a run left running by an earlier night is failed as abandoned")
    void abandonedRun() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder().runId(runId).workspaceId(workspaceId)
                    .projectId(projectId).mode(TopicRun.Mode.FIRST_BUILD).status(TopicRun.Status.RUNNING)
                    .startedAt(Instant.now().minus(Duration.ofDays(1))).build());
            return null;
        });

        nightlyService.runNight(Instant.now());

        assertThat(runs(projectId)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
            assertThat(run.errorCode()).isEqualTo("abandoned");
        });
    }

    @Test
    @DisplayName("a project whose last 3 runs failed is paused for 7 nights from the last failure, and a run by hand "
            + "ignores the pause")
    void pauseAfterThreeFailures() {
        var workspaceId = enabledWorkspace();
        var today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        var paused = UUID.randomUUID();
        var lastPausedNight = UUID.randomUUID();
        var pauseOver = UUID.randomUUID();
        var twoFailures = UUID.randomUUID();
        var savedBetween = UUID.randomUUID();
        for (var projectId : List.of(paused, lastPausedNight, pauseOver, twoFailures, savedBetween)) {
            seed(workspaceId, projectId, 0, BUILT, FIXTURE.night, today);
        }
        insertRun(workspaceId, paused, 3, TopicRun.Status.FAILED, "qwen_error");
        insertRun(workspaceId, paused, 2, TopicRun.Status.FAILED, "qwen_error");
        insertRun(workspaceId, paused, 1, TopicRun.Status.FAILED, "abandoned");
        for (int daysAgo : List.of(9, 8, 7)) {
            insertRun(workspaceId, lastPausedNight, daysAgo, TopicRun.Status.FAILED, "timeout");
        }
        for (int daysAgo : List.of(10, 9, 8)) {
            insertRun(workspaceId, pauseOver, daysAgo, TopicRun.Status.FAILED, "timeout");
        }
        for (int daysAgo : List.of(2, 1)) {
            insertRun(workspaceId, twoFailures, daysAgo, TopicRun.Status.FAILED, "timeout");
        }
        insertRun(workspaceId, savedBetween, 4, TopicRun.Status.FAILED, "timeout");
        insertRun(workspaceId, savedBetween, 3, TopicRun.Status.SAVED, null);
        insertRun(workspaceId, savedBetween, 2, TopicRun.Status.FAILED, "timeout");
        insertRun(workspaceId, savedBetween, 1, TopicRun.Status.FAILED, "timeout");
        stubAnswer(FIXTURE.answer);

        var result = nightlyService.runNight(today.plus(Duration.ofHours(2)));

        assertThat(result.saved()).isEqualTo(3);
        template.inTransaction(READ_ONLY, handle -> {
            assertThat(handle.attach(TopicNightDAO.class).findProjects(LocalDate.ofInstant(today, ZoneOffset.UTC)))
                    .filteredOn(row -> workspaceId.equals(row.workspaceId()))
                    .extracting(TopicNightProject::projectId, TopicNightProject::decision, TopicNightProject::reason)
                    .containsExactlyInAnyOrder(
                            tuple(paused, "skip", "paused"),
                            tuple(lastPausedNight, "skip", "paused"),
                            tuple(pauseOver, "first_build", null),
                            tuple(twoFailures, "first_build", null),
                            tuple(savedBetween, "first_build", null));
            return null;
        });
        assertThat(runs(paused)).hasSize(3);
        assertThat(runs(lastPausedNight)).hasSize(3);

        try (var response = rebuild(workspaceId, paused)) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(runs(paused))
                .hasSize(4).last().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
    }

    @Test
    @DisplayName("the first-build job builds a project with minVectors in tomorrow's window, for tomorrow; it skips one "
            + "with a list, with a run in the last 24 h, paused, or under minVectors")
    void firstBuildsByDay() {
        var workspaceId = enabledWorkspace();
        var tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        var tomorrowStart = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant();
        var built = UUID.randomUUID();
        var withList = UUID.randomUUID();
        var recentRun = UUID.randomUUID();
        var paused = UUID.randomUUID();
        var small = UUID.randomUUID();
        for (var projectId : List.of(built, withList, recentRun, paused)) {
            seed(workspaceId, projectId, 0, BUILT, FIXTURE.night, tomorrowStart);
        }
        seed(workspaceId, small, BUILT, SMALL, FIXTURE.night, tomorrowStart);
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertState(TopicProjectState.builder()
                    .projectId(withList)
                    .workspaceId(workspaceId)
                    .rebuilds(1)
                    .firstBuiltAt(Instant.now())
                    .lastRunId(UUID.randomUUID())
                    .lastSuccessAt(Instant.now())
                    .windowUntil(tomorrowStart)
                    .build());
            return null;
        });
        insertRun(workspaceId, recentRun, 0, TopicRun.Status.FAILED, "qwen_error");
        for (int daysAgo : List.of(4, 3, 2)) {
            insertRun(workspaceId, paused, daysAgo, TopicRun.Status.FAILED, "timeout");
        }
        stubAnswer(FIXTURE.answer);

        // The job's own filters: a run by hand would refuse the projects with a list or under minVectors anyway.
        assertThat(nightlyService.firstBuildCandidates(tomorrow, Instant.now()))
                .containsExactly(new TopicsNightlyService.Candidate(workspaceId, built, BUILT));

        runFirstBuildsUntilBuilt(built);

        assertThat(runs(built)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED);
            assertThat(json(run.stats()).path("vectors").asInt()).isEqualTo(BUILT);
        });
        assertThat(topics(built)).isNotEmpty().allSatisfy(topic -> assertThat(topic.born()).isEqualTo(tomorrow));
        assertThat(state(built).windowUntil()).isEqualTo(tomorrowStart);
        assertThat(runs(withList)).isEmpty();
        assertThat(runs(recentRun)).hasSize(1);
        assertThat(runs(paused)).hasSize(3);
        assertThat(runs(small)).isEmpty();
    }

    @Test
    @DisplayName("the first-build job starts nothing while the night holds its lock, and builds once it is released")
    void firstBuildsWaitForTheNight() throws InterruptedException {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT, FIXTURE.night,
                LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        stubAnswer(FIXTURE.answer);
        var nightStarted = new CountDownLatch(1);
        var nightEnds = new CountDownLatch(1);
        var night = lockService.bestEffortLock(
                TopicsNightlyService.NIGHT_LOCK,
                Mono.fromCallable(() -> {
                    nightStarted.countDown();
                    nightEnds.await();
                    return true;
                }).subscribeOn(Schedulers.boundedElastic()),
                Mono.empty(),
                Duration.ofMinutes(1),
                Duration.ZERO)
                .subscribe();
        try {
            assertThat(nightStarted.await(10, TimeUnit.SECONDS)).isTrue();

            nightlyService.runFirstBuilds(Instant.now());

            assertThat(runs(projectId)).isEmpty();
        } finally {
            nightEnds.countDown();
        }
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> Boolean.FALSE.equals(lockService.isLocked(TopicsNightlyService.NIGHT_LOCK).block()));
        night.dispose();

        runFirstBuildsUntilBuilt(projectId);

        assertThat(runs(projectId)).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED));
    }

    @Test
    @DisplayName("housekeeping deletes the runs started before the runs retention with their counts, a run saved after "
            + "the cut included, and keeps newer ones")
    void housekeepingDeletesOldRunCounts() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var cut = Instant.now().minus(Duration.ofDays(90));
        var old = UUID.randomUUID();
        // Started just before the cut and saved just after it: its counts must go with the run.
        var acrossTheCut = UUID.randomUUID();
        var recent = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            var runDAO = handle.attach(TopicRunDAO.class);
            runDAO.insert(savedRun(old, workspaceId, projectId, cut.minus(Duration.ofDays(1))));
            runDAO.insert(savedRun(acrossTheCut, workspaceId, projectId, cut.minus(Duration.ofMinutes(1))));
            runDAO.insert(savedRun(recent, workspaceId, projectId, Instant.now().minus(Duration.ofDays(1))));
            runDAO.insertCounts(List.of(
                    otherCount(old, workspaceId, projectId, cut.minus(Duration.ofDays(1))),
                    otherCount(acrossTheCut, workspaceId, projectId, cut.plus(Duration.ofMinutes(1))),
                    otherCount(recent, workspaceId, projectId, Instant.now().minus(Duration.ofDays(1)))));
            return null;
        });

        nightlyService.runNight(Instant.now());

        assertThat(runs(projectId)).extracting(TopicRun::runId).containsExactly(recent);
        assertThat(counts(projectId)).extracting(TopicRunCount::runId).containsExactly(recent);
    }

    private static TopicRun savedRun(UUID runId, String workspaceId, UUID projectId, Instant startedAt) {
        return TopicRun.builder()
                .runId(runId)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .mode(TopicRun.Mode.FIRST_BUILD)
                .status(TopicRun.Status.SAVED)
                .startedAt(startedAt)
                .build();
    }

    @Test
    @DisplayName("the window count runs on 2 threads under the row guard at a background priority; the window read "
            + "stops after 120 s")
    void readsCarryTheirGuards() {
        var workspaceId = enabledWorkspace();
        seed(workspaceId, UUID.randomUUID(), 0, BUILT);
        stubAnswer(FIXTURE.answer);

        assertThat(nightlyService.runNight(start()).saved()).isEqualTo(1);

        var maxRowsToRead = String.valueOf(opikConfig.getTopics().getVectors().getFind().getMaxRowsToRead());
        assertThat(guards("topics:count_window", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "100", "60"));
        assertThat(guards("topics:read_window", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "", "120"));
    }

    @Test
    @DisplayName("the window count and the window read skip the vectors of deleted traces")
    void deletedVectorsAreNotRead() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        var deleted = FIXTURE.traceIds.subList(0, 10);
        // deleteByKeys runs deleteByTraceIds, the trace-delete cascade's delete, as the system user.
        vectorsDAO.deleteByKeys(workspaceId, deleted.stream()
                .map(traceId -> TopicTraceKey.builder().projectId(projectId).traceId(traceId).build())
                .toList()).block();

        var to = night().atStartOfDay(ZoneOffset.UTC).toInstant();
        var from = night().minusDays(opikConfig.getTopics().getNightly().getWindowDays())
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(windowDAO.countByProject(workspaceId, projectId, from, to, 60).collectList().block())
                .containsExactly(new TopicWindowDAO.ProjectCount(projectId, BUILT - deleted.size()));
        assertThat(windowDAO.readWindow(workspaceId, projectId, from, to, BUILT, 60).collectList().block())
                .extracting(TopicWindowRow::traceId)
                .containsExactlyInAnyOrderElementsOf(FIXTURE.traceIds.subList(deleted.size(), BUILT));
    }

    /**
     * Runs the first-build job's run until it has built the project: a run stops at the first project that cannot
     * start, as when a run by hand or a night of an earlier test still holds its lock for a moment.
     */
    private void runFirstBuildsUntilBuilt(UUID projectId) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            nightlyService.runFirstBuilds(Instant.now());
            assertThat(runs(projectId)).isNotEmpty();
        });
    }

    private void assertFailedWithNothingWritten(String workspaceId, UUID projectId, String errorCode) {
        assertThat(runs(projectId)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
            assertThat(run.errorCode()).isEqualTo(errorCode);
            assertThat(run.errorMessage()).isNotBlank();
            assertThat(json(run.stats()).path("vectors").asInt()).isEqualTo(BUILT);
        });
        assertThat(topics(projectId)).isEmpty();
        assertThat(prototypes(projectId)).isEmpty();
        assertThat(state(projectId)).isNull();
        assertThat(counts(projectId)).isEmpty();
        assertThat(traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block()).isEmpty();
    }

    /** A finished run of the project, started at 02:00 UTC {@code daysAgo} days before today. */
    private void insertRun(String workspaceId, UUID projectId, int daysAgo, TopicRun.Status status,
            String errorCode) {
        var startedAt = LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).atStartOfDay(ZoneOffset.UTC).toInstant()
                .plus(Duration.ofHours(2));
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder().runId(UUID.randomUUID())
                    .workspaceId(workspaceId).projectId(projectId).mode(TopicRun.Mode.FIRST_BUILD).status(status)
                    .startedAt(startedAt).finishedAt(startedAt.plus(Duration.ofMinutes(1))).errorCode(errorCode)
                    .build());
            return null;
        });
    }

    private static TopicRunCount otherCount(UUID runId, String workspaceId, UUID projectId, Instant savedAt) {
        return TopicRunCount.builder()
                .runId(runId)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .topicId(TraceTopic.OTHER)
                .traces(10)
                .savedAt(savedAt)
                .build();
    }

    /** The night after the fixture's last trace: its window is the fixture's. */
    private static Instant start() {
        return FIXTURE.night.plus(Duration.ofHours(2));
    }

    private static LocalDate night() {
        return LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);
    }

    private String enabledWorkspace() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var topics = opikConfig.getTopics();
        var listed = new TreeSet<>(topics.enabledWorkspaceIds());
        listed.add(workspaceId);
        topics.setEnabledWorkspaces(String.join(",", listed));
        return workspaceId;
    }

    /**
     * Opted in to ai-labels only, not listed in topics.enabledWorkspaces: its vectors exist (written before topics were
     * turned off for it).
     */
    private String aiLabelsOnlyWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        return workspaceId;
    }

    private Response runNight(String night) {
        var target = client.target("%s/v1/internal/topics/nights/run".formatted(baseUrl));
        return (night == null ? target : target.queryParam("night", night))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    private Response rebuild(String workspaceId, UUID projectId) {
        return rebuild(workspaceId, projectId, null);
    }

    private Response rebuild(String workspaceId, UUID projectId, String night) {
        var target = client.target("%s/v1/internal/topics/projects/%s/run".formatted(baseUrl, projectId))
                .queryParam("workspace_id", workspaceId);
        return (night == null ? target : target.queryParam("night", night))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    /** The fixture's traces {@code [from, from + count)} as the project's vectors. */
    private void seed(String workspaceId, UUID projectId, int from, int count) {
        seed(workspaceId, projectId, from, count, FIXTURE.night, FIXTURE.night);
    }

    /**
     * As {@link #seed(String, UUID, int, int)}, with each trace's time moved from the fixture's night to
     * {@code night}, so the window keeps the same traces.
     */
    private void seed(String workspaceId, UUID projectId, int from, int count, Instant fixtureNight,
            Instant night) {
        var shift = Duration.between(fixtureNight, night);
        template.inTransaction(WRITE, handle -> handle.execute(
                "INSERT INTO projects (id, name, workspace_id) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE id = id",
                projectId.toString(), "topics-" + projectId, workspaceId));
        var rows = new ArrayList<TopicVector>(count);
        for (int i = from; i < from + count; i++) {
            var vector = new ArrayList<Float>(TopicsFixture.DIMENSIONS);
            for (int t = 0; t < TopicsFixture.DIMENSIONS; t++) {
                vector.add(FIXTURE.vectors[i * TopicsFixture.DIMENSIONS + t]);
            }
            rows.add(TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .traceId(FIXTURE.traceIds.get(i))
                    .traceCreatedAt(FIXTURE.createdAt.get(i).plus(shift))
                    .taskVersion(1)
                    .embedModel(TopicVectorsTickService.EMBED_MODEL)
                    .embedDimensions(TopicVectorsTickService.EMBED_DIMENSIONS)
                    .text(FIXTURE.intents[i])
                    .vector(vector)
                    .build());
        }
        vectorsDAO.insert(rows).block();
    }

    private void stubAnswer(JsonNode output) {
        stubAnswer(output, 0);
    }

    private void stubAnswer(JsonNode output, int delayMillis) {
        ObjectNode body = InferenceJson.MAPPER.createObjectNode();
        body.put("request_id", "r-1");
        body.putObject("usage").put("input_tokens", 1_000).put("output_tokens", 200);
        body.put("cache_hit", false);
        body.put("latency_ms", 1_500);
        body.putObject("result").set("output", output);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(delayMillis)
                .withBody(body.toString())));
    }

    private List<TopicRun> runs(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicRunDAO.class).findByProject(projectId));
    }

    private List<Topic> topics(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class).findLive(projectId));
    }

    private List<TopicRunCount> counts(UUID projectId) {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicRunDAO.class).findCountsByProject(projectId));
    }

    private List<TopicPrototype> prototypes(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class).findPrototypes(projectId));
    }

    private TopicProjectState state(UUID projectId) {
        var states = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findStates(Set.of(projectId)));
        return states.isEmpty() ? null : states.getFirst();
    }

    /**
     * The distinct {@code max_threads}, {@code max_rows_to_read}, {@code priority} and {@code max_execution_time} the
     * workspace's finished {@code queryName} queries ran with, an empty string for a setting left at its default.
     */
    private List<List<String>> guards(String queryName, String workspaceId) {
        return clickHouseTemplate.nonTransaction(connection -> Mono
                .from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .thenMany(Flux.from(connection.createStatement("""
                        SELECT DISTINCT Settings['max_threads'] AS max_threads,
                            Settings['max_rows_to_read'] AS max_rows_to_read, Settings['priority'] AS priority,
                            Settings['max_execution_time'] AS max_execution_time
                        FROM system.query_log
                        WHERE type = 'QueryFinish' AND is_initial_query AND log_comment LIKE :log_comment
                        """)
                        .bind("log_comment", queryName + ":" + workspaceId + ":%")
                        .execute()))
                .flatMap(result -> result.map((row, metadata) -> List.of(row.get("max_threads", String.class),
                        row.get("max_rows_to_read", String.class), row.get("priority", String.class),
                        row.get("max_execution_time", String.class))))
                .collectList())
                .block();
    }

    private static JsonNode json(String text) {
        try {
            return InferenceJson.MAPPER.readTree(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
