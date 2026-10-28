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
import com.comet.opik.api.resources.v1.jobs.TopicsNightlyJob;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.inference.InferenceJson;
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
import org.quartz.impl.matchers.GroupMatcher;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The topics nightly job against real ClickHouse, MySQL and Redis, with opik-inference's {@code /v1/generate} stubbed
 * in WireMock. Tests seed {@code topic_vectors} with the fixture's traces (its texts, vectors and times, so the night
 * is the fixture's) and call {@link TopicsNightlyService#runNight(Instant)}; the scheduled job fires at 02:00 UTC only.
 */
@DisplayName("topics nightly job")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsNightlyJobTest {

    private static final String GENERATE_PATH = "/v1/generate";
    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int BUILT = 120;
    private static final int SMALL = 50;
    private static final TopicsNightlyFixture NIGHTLY = TopicsNightlyFixture.load();
    private static final int PLANTED = 60;
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final int REVIEW_REBUILDS = 7;
    private static final JsonNode EMPTY = InferenceJson.MAPPER.createObjectNode();
    private static final JsonNode NAMED = named();
    /** Text only the first naming prompt has. */
    private static final String NAMING_PROMPT = "Decide what topic each one gets.";

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
    private TopicsNightlyService nightlyService;
    private TopicVectorsDAO vectorsDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicsProjectDeletedListener projectDeletedListener;
    private GuiceJobManager jobManager;
    private ClientSupport client;
    private String baseUrl;

    @BeforeAll
    void beforeAll(ClientSupport client, TransactionTemplate template, GuiceJobManager jobManager,
            @Jit TopicsNightlyService nightlyService, @Jit TopicVectorsDAO vectorsDAO,
            @Jit TraceTopicsDAO traceTopicsDAO, @Jit TopicsProjectDeletedListener projectDeletedListener) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.template = template;
        this.jobManager = jobManager;
        this.nightlyService = nightlyService;
        this.vectorsDAO = vectorsDAO;
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
            handle.execute("UPDATE topic_workspaces SET enabled = FALSE");
            return null;
        });
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
            assertThat(stats.path("k").asInt()).isEqualTo(15);
            assertThat(stats.path("seed").asInt()).isZero();
            assertThat(stats.path("provisional").asBoolean()).isTrue();
            assertThat(stats.path("qwen_calls").asInt()).isEqualTo(1);
            assertThat(stats.path("task_version_shares").path("1").asDouble()).isEqualTo(1.0);
            assertThat(stats.path("seconds").has("kmeans")).isTrue();
        });
        var runId = run.getFirst().runId();

        var topics = topics(built);
        assertThat(topics).isNotEmpty().hasSizeLessThanOrEqualTo(TopicsRebuildService.MAX_TOPICS);
        assertThat(topics).allSatisfy(topic -> {
            assertThat(topic.workspaceId()).isEqualTo(workspaceId);
            assertThat(topic.born()).isEqualTo(night());
            assertThat(topic.retiredAt()).isNull();
            assertThat(AuthorApplier.isCatchAll(topic.name())).isFalse();
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
            assertThat(body.path("messages").get(0).path("content").asText()).contains("cluster 14:");
        });

        // The next night: the project has a list, so it gets a nightly update.
        stubNightly(EMPTY, null, null);
        var again = nightlyService.runNight(start().plus(Duration.ofDays(1)));

        assertThat(again.saved()).isEqualTo(1);
        assertThat(runs(built)).hasSize(2).filteredOn(saved -> saved.mode() == TopicRun.Mode.NIGHTLY_UPDATE)
                .singleElement().satisfies(update -> {
                    assertThat(update.status()).isEqualTo(TopicRun.Status.SAVED);
                    var stats = json(update.stats());
                    assertThat(stats.path("rebuilds_before").asInt()).isEqualTo(1);
                    assertThat(stats.path("review_night").asBoolean()).isFalse();
                    // Every trace of tonight's window was labelled last night.
                    assertThat(stats.path("churn_any").isNumber()).isTrue();
                });
        assertThat(state(built).rebuilds()).isEqualTo(2);
    }

    @Test
    @DisplayName("a review night merges two topics, keeps a big topic Qwen retires, births the planted theme, saves every column")
    void nightlyUpdate() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var before = topics(projectId);
        forceReview(projectId);
        plant(workspaceId, projectId, PLANTED);
        var revise = InferenceJson.MAPPER.createObjectNode();
        // Topic 1 holds far more than 2% of the window: the review keeps it. Topic 2 is merged into it.
        revise.putArray("retire").add(1);
        revise.putArray("merge").addArray().add(1).add(2);
        var clusters = revise.putArray("new").addObject().put("name", "Tire Pressure Checks")
                .put("definition", "Asks whether the car's tires need air.").putArray("clusters");
        for (int j = 0; j < 20; j++) {
            clusters.add(j);
        }
        revise.putArray("other");
        stubNightly(revise, sameAnswers(), null);

        var result = nightlyService.runNight(secondStart());

        assertThat(result.saved()).isEqualTo(1);
        var update = nightlyRun(projectId);
        var stats = json(update.stats());
        assertThat(stats.path("review_night").asBoolean()).isTrue();
        assertThat(stats.path("review").asBoolean()).isTrue();
        assertThat(stats.path("candidates").asInt()).isPositive();
        assertThat(stats.path("retired").path("review").asInt()).isZero();
        assertThat(stats.path("retired").path("merged").asInt()).isEqualTo(1);
        assertThat(items(stats.path("born"))).extracting(JsonNode::asText).contains("Tire Pressure Checks");
        assertThat(stats.path("churn").isNumber()).isTrue();
        assertThat(stats.path("qwen").findValuesAsText("kind")).startsWith("revise", "verify");
        // The service decodes the keys in schema order: assign first in revise, answers in verify.
        var bodies = wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH))).stream()
                .map(request -> request.getBodyAsString()).toList();
        assertThat(bodies).anySatisfy(body -> assertThat(body).contains("You maintain the topic list")
                .contains("\"schema\":{\"type\":\"object\",\"properties\":{\"assign\":"));
        assertThat(bodies).anySatisfy(body -> assertThat(body)
                .contains("\"schema\":{\"type\":\"object\",\"properties\":{\"answers\":"));

        var keptId = before.getFirst().id();
        var retiredId = before.get(1).id();
        var retired = allTopics(projectId).stream().filter(topic -> topic.id().equals(retiredId)).findFirst()
                .orElseThrow();
        assertThat(retired.retiredAt()).isEqualTo(secondNight());
        assertThat(retired.retiredReason()).isEqualTo("merged");
        assertThat(retired.name()).isEqualTo(before.get(1).name());

        var live = topics(projectId);
        assertThat(live).extracting(Topic::id).contains(keptId).doesNotContain(retiredId);
        assertThat(live).hasSizeLessThanOrEqualTo(TopicsRebuildService.MAX_TOPICS);
        assertThat(live).filteredOn(topic -> topic.name().equals("Tire Pressure Checks")).singleElement()
                .satisfies(topic -> {
                    assertThat(topic.born()).isEqualTo(secondNight());
                    assertThat(topic.size()).isPositive();
                });
        var liveIds = live.stream().map(Topic::id).collect(Collectors.toSet());

        var prototypes = prototypes(projectId);
        assertThat(prototypes).extracting(TopicPrototype::position)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, prototypes.size()).boxed().toList());
        assertThat(prototypes).allSatisfy(prototype -> assertThat(
                prototype.topicId() == null || liveIds.contains(prototype.topicId())).isTrue());

        var labels = traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block().stream()
                .filter(row -> row.runId().equals(update.runId().toString())).toList();
        assertThat(labels).hasSize(stats.path("vectors").asInt())
                .allSatisfy(row -> assertThat(row.topicId().equals(TraceTopic.OTHER)
                        || liveIds.contains(row.topicId())).isTrue());
        live.forEach(topic -> assertThat(labels.stream().filter(row -> row.topicId().equals(topic.id())))
                .hasSize(topic.size()));
        assertThat(state(projectId).rebuilds()).isEqualTo(REVIEW_REBUILDS + 1);
    }

    @Test
    @DisplayName("a failing verify call fails the run and leaves the previous night's list")
    void verifyFails() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var topics = topics(projectId);
        forceReview(projectId);
        plant(workspaceId, projectId, PLANTED);
        var revise = InferenceJson.MAPPER.createObjectNode();
        var assign = revise.putArray("assign");
        for (int j = 0; j < 20; j++) {
            assign.addObject().put("cluster", j).put("topic", 1);
        }
        revise.putArray("new");
        revise.putArray("other");
        stubNightly(revise, null, NAMED);

        var result = nightlyService.runNight(secondStart());

        // Read as "no pair verified", a failed verify would turn revise's assigns into forced births.
        assertThat(result.failed()).isEqualTo(1);
        var update = nightlyRun(projectId);
        assertThat(update.status()).isEqualTo(TopicRun.Status.FAILED);
        assertThat(update.errorCode()).isEqualTo("qwen_error");
        assertThat(topics(projectId)).isEqualTo(topics);
        assertThat(state(projectId).rebuilds()).isEqualTo(REVIEW_REBUILDS);
    }

    @Test
    @DisplayName("by hand, a project with a list gets a nightly update, and the same night is not run twice")
    void nightlyUpdateByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        stubNightly(emptyRevise(), sameAnswers(), NAMED);

        int status;
        try (var response = rebuild(workspaceId, projectId, secondNight().toString())) {
            status = response.getStatus();
        }

        assertThat(status).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(nightlyRun(projectId)
                .status()).isEqualTo(TopicRun.Status.SAVED));
        assertThat(state(projectId).rebuilds()).isEqualTo(2);
        try (var response = rebuild(workspaceId, projectId, secondNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }

        var result = nightlyService.runNight(secondStart());

        assertThat(result.saved()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(runs(projectId)).hasSize(2);
    }

    @Test
    @DisplayName("a topic born on the traces of one retired for low share is that topic again, with its id and name")
    void rebirth() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        // A tire topic was retired for low share yesterday; its prototype stays, and the tire traffic comes back.
        var dormant = Topic.builder()
                .id(ID_GENERATOR.generateId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name("Tire Questions")
                .definition("Questions about the car's tires.")
                .born(night())
                .retiredAt(secondNight().minusDays(1))
                .retiredReason("low_share")
                .lowNights(3)
                .build();
        int position = prototypes(projectId).size();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicDAO.class);
            dao.insertTopics(List.of(dormant));
            dao.insertPrototypes(List.of(TopicPrototype.builder()
                    .id(ID_GENERATOR.generateId())
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .position(position)
                    .topicId(dormant.id())
                    .vector(TopicPrototype.encode(tireCentre()))
                    .build()));
            return null;
        });
        plant(workspaceId, projectId, PLANTED);
        var revise = InferenceJson.MAPPER.createObjectNode();
        revise.putArray("assign");
        var clusters = revise.putArray("new").addObject().put("name", "Tire Pressure Checks")
                .put("definition", "Asks whether the car's tires need air.").putArray("clusters");
        for (int j = 0; j < 20; j++) {
            clusters.add(j);
        }
        revise.putArray("other");
        stubNightly(revise, sameAnswers(), NAMED);

        assertThat(nightlyService.runNight(secondStart()).saved()).isEqualTo(1);

        var live = topics(projectId);
        assertThat(live).extracting(Topic::name).doesNotContain("Tire Pressure Checks");
        assertThat(live).filteredOn(topic -> topic.id().equals(dormant.id())).singleElement().satisfies(topic -> {
            assertThat(topic.name()).isEqualTo("Tire Questions");
            assertThat(topic.definition()).isEqualTo("Questions about the car's tires.");
            assertThat(topic.born()).isEqualTo(night());
            assertThat(topic.retiredReason()).isNull();
            assertThat(topic.revivedAt()).isEqualTo(secondNight());
            assertThat(topic.lowNights()).isZero();
            assertThat(topic.size()).isPositive();
        });
        var stats = json(nightlyRun(projectId).stats());
        assertThat(stats.path("revived")).extracting(JsonNode::asText).containsExactly("Tire Questions");
        assertThat(stats.path("born")).isEmpty();
    }

    @Test
    @DisplayName("three nights count the rebuilds and keep the first build's night")
    void threeNights() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        // The fixture's traces are 21 to 29 days old on its night: 5 days later, all three windows hold all of them.
        seed(workspaceId, projectId, 0, BUILT, FIXTURE.night, FIXTURE.night.plus(Duration.ofDays(5)));
        stubAnswer(FIXTURE.answer);
        assertThat(nightlyService.runNight(start()).saved()).isEqualTo(1);
        wireMock.resetAll();
        stubNightly(emptyRevise(), sameAnswers(), NAMED);

        assertThat(nightlyService.runNight(secondStart()).saved()).isEqualTo(1);
        assertThat(nightlyService.runNight(secondStart().plus(Duration.ofDays(1))).saved()).isEqualTo(1);

        var state = state(projectId);
        assertThat(state.rebuilds()).isEqualTo(3);
        assertThat(state.firstBuiltAt()).isEqualTo(night().atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(state.windowUntil())
                .isEqualTo(secondNight().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(runs(projectId)).extracting(TopicRun::status).containsOnly(TopicRun.Status.SAVED);
        assertThat(topics(projectId)).isNotEmpty().allSatisfy(topic -> assertThat(topic.born()).isEqualTo(night()));
    }

    @Test
    @DisplayName("a failing revise call fails the run and leaves the previous night's list")
    void reviseFails() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var topics = topics(projectId);
        var prototypes = prototypes(projectId);
        var labels = traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block();
        forceReview(projectId);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)));

        var result = nightlyService.runNight(secondStart());

        assertThat(result.failed()).isEqualTo(1);
        var update = nightlyRun(projectId);
        assertThat(update.status()).isEqualTo(TopicRun.Status.FAILED);
        assertThat(update.errorCode()).isEqualTo("qwen_error");
        assertThat(topics(projectId)).isEqualTo(topics);
        assertThat(prototypes(projectId)).extracting(TopicPrototype::id)
                .containsExactlyElementsOf(prototypes.stream().map(TopicPrototype::id).toList());
        assertThat(state(projectId).rebuilds()).isEqualTo(REVIEW_REBUILDS);
        assertThat(traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block())
                .containsExactlyInAnyOrderElementsOf(labels);
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
        for (int j = 0; j < 11; j++) {
            topics.addObject().put("name", "Topic " + j).put("definition", "Requests of kind " + j)
                    .putArray("clusters").add(j);
        }
        answer.putArray("other").add(11).add(12).add(13).add(14);
        stubAnswer(answer);

        var result = nightlyService.runNight(start());

        assertThat(result.saved()).isEqualTo(1);
        assertThat(topics(projectId)).hasSize(10);
        assertThat(json(runs(projectId).getFirst().stats()).path("other_groups").asInt()).isEqualTo(5);
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
    @DisplayName("a run whose project another run saved meanwhile fails with save and takes its labels back")
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
        List<TopicProjectState> states = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findStates(Set.of(projectId)));
        assertThat(states).isEmpty();
        assertThat(traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("deleting a project deletes its topics, prototypes, state and runs")
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
        assertThat(topics(kept)).isNotEmpty();
        assertThat(runs(kept)).hasSize(1);
    }

    @Test
    @DisplayName("the internal endpoint starts a first build now, and refuses the same night twice")
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

    /** A project with a saved first build on the fixture's night. */
    private UUID firstBuilt(String workspaceId) {
        var projectId = UUID.randomUUID();
        seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(FIXTURE.answer);
        assertThat(nightlyService.runNight(start()).saved()).isEqualTo(1);
        wireMock.resetAll();
        return projectId;
    }

    /** Stage 2b's way to force a review night by hand: {@code rebuilds} a multiple of 7. */
    private void forceReview(UUID projectId) {
        template.inTransaction(WRITE, handle -> handle.execute(
                "UPDATE topic_project_states SET rebuilds = ? WHERE project_id = ?", REVIEW_REBUILDS,
                projectId.toString()));
    }

    /** {@code count} planted traces of the nightly fixture's tire theme, created the day before the second night. */
    private void plant(String workspaceId, UUID projectId, int count) {
        var rows = new ArrayList<TopicVector>(count);
        var day = secondNight().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        for (int i = 0; i < NIGHTLY.intents.length && rows.size() < count; i++) {
            if (!NIGHTLY.intents[i].toLowerCase().contains("tire")) {
                continue;
            }
            var vector = new ArrayList<Float>(TopicsNightlyFixture.DIMENSIONS);
            for (int t = 0; t < TopicsNightlyFixture.DIMENSIONS; t++) {
                vector.add(NIGHTLY.vectors[i * TopicsNightlyFixture.DIMENSIONS + t]);
            }
            rows.add(TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .traceId(ID_GENERATOR.generateId())
                    .traceCreatedAt(day.plus(Duration.ofMinutes(rows.size())))
                    .taskVersion(1)
                    .embedModel(TopicVectorsTickService.EMBED_MODEL)
                    .embedDimensions(TopicVectorsTickService.EMBED_DIMENSIONS)
                    .text(NIGHTLY.intents[i])
                    .vector(vector)
                    .build());
        }
        assertThat(rows).hasSize(count);
        vectorsDAO.insert(rows).block();
    }

    /** The unit mean of the first {@value #PLANTED} tire vectors of the nightly fixture. */
    private static float[] tireCentre() {
        int d = TopicsNightlyFixture.DIMENSIONS;
        var sum = new double[d];
        int count = 0;
        for (int i = 0; i < NIGHTLY.intents.length && count < PLANTED; i++) {
            if (NIGHTLY.intents[i].toLowerCase().contains("tire")) {
                for (int t = 0; t < d; t++) {
                    sum[t] += NIGHTLY.vectors[i * d + t];
                }
                count++;
            }
        }
        double norm = Math.sqrt(java.util.Arrays.stream(sum).map(value -> value * value).sum());
        var out = new float[d];
        for (int t = 0; t < d; t++) {
            out[t] = (float) (sum[t] / norm);
        }
        return out;
    }

    /**
     * Stubs the nightly update's calls by prompt: revise (both prompts), verify (a 500 when {@code verify} is null) and
     * the first naming call (a 500 when {@code naming} is null).
     */
    private void stubNightly(JsonNode revise, JsonNode verify, JsonNode naming) {
        stubAnswer(revise, "You maintain the topic list");
        if (verify == null) {
            wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).withRequestBody(containing("You check decisions"))
                    .willReturn(aResponse().withStatus(500)));
        } else {
            stubAnswer(verify, "You check decisions");
        }
        // After the revise stub: the naming prompt also starts like the revise prompt, and the newest stub wins.
        if (naming == null) {
            wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).withRequestBody(containing(NAMING_PROMPT))
                    .willReturn(aResponse().withStatus(500)));
        } else {
            stubAnswer(naming, NAMING_PROMPT);
        }
    }

    /** A revise answer that changes nothing. */
    private static JsonNode emptyRevise() {
        var revise = InferenceJson.MAPPER.createObjectNode();
        revise.putArray("assign");
        revise.putArray("new");
        revise.putArray("other");
        return revise;
    }

    private static JsonNode sameAnswers() {
        var answer = InferenceJson.MAPPER.createObjectNode();
        var answers = answer.putArray("answers");
        for (int pair = 0; pair < 20; pair++) {
            answers.addObject().put("pair", pair).put("same", true);
        }
        return answer;
    }

    private TopicRun nightlyRun(UUID projectId) {
        return runs(projectId).stream().filter(run -> run.mode() == TopicRun.Mode.NIGHTLY_UPDATE).findFirst()
                .orElseThrow();
    }

    private static Instant secondStart() {
        return start().plus(Duration.ofDays(1));
    }

    private static LocalDate secondNight() {
        return night().plusDays(1);
    }

    private List<Topic> allTopics(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> handle.createQuery(
                "SELECT * FROM topics WHERE project_id = :projectId").bind("projectId", projectId.toString())
                .map(org.jdbi.v3.core.mapper.reflect.ConstructorMapper.of(Topic.class)).list());
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
        assertThat(traceTopicsDAO.findByProject(workspaceId, projectId).collectList().block()).isEmpty();
    }

    /** The night after the fixture's last trace: its window is the fixture's. */
    private static Instant start() {
        return FIXTURE.night.plus(Duration.ofHours(2));
    }

    private static LocalDate night() {
        return LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);
    }

    private String enabledWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            handle.attach(TopicWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        return workspaceId;
    }

    /** Opted in to ai-labels only: its vectors exist (written before topics were turned off for it). */
    private String aiLabelsOnlyWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            handle.attach(TopicWorkspaceDAO.class).upsert(workspaceId, false, "test");
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
        stubAnswer(output, null, 0);
    }

    /** Answers {@code output} to the calls whose body contains {@code prompt}, or to every call when it is null. */
    private void stubAnswer(JsonNode output, String prompt) {
        stubAnswer(output, prompt, 0);
    }

    private void stubAnswer(JsonNode output, int delayMillis) {
        stubAnswer(output, null, delayMillis);
    }

    private void stubAnswer(JsonNode output, String prompt, int delayMillis) {
        ObjectNode body = InferenceJson.MAPPER.createObjectNode();
        body.put("request_id", "r-1");
        body.putObject("usage").put("input_tokens", 1_000).put("output_tokens", 200);
        body.put("cache_hit", false);
        body.put("latency_ms", 1_500);
        body.putObject("result").set("output", output);
        var request = post(urlEqualTo(GENERATE_PATH));
        if (prompt != null) {
            request = request.withRequestBody(containing(prompt));
        }
        wireMock.stubFor(request.willReturn(aResponse().withStatus(200)
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

    private List<TopicPrototype> prototypes(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class).findPrototypes(projectId));
    }

    private TopicProjectState state(UUID projectId) {
        var states = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findStates(Set.of(projectId)));
        return states.isEmpty() ? null : states.getFirst();
    }

    private static List<JsonNode> items(JsonNode array) {
        var out = new ArrayList<JsonNode>();
        array.forEach(out::add);
        return out;
    }

    private static JsonNode named() {
        var answer = InferenceJson.MAPPER.createObjectNode();
        answer.putArray("assign");
        // Every group id the night can have: the answer keeps only the forced ones, as one new topic.
        var clusters = answer.putArray("new").addObject().put("name", "Tire Pressure Checks")
                .put("definition", "Asks whether the car's tires need air.").putArray("clusters");
        for (int j = 0; j < 100; j++) {
            clusters.add(j);
        }
        answer.putArray("other");
        return answer;
    }

    private static JsonNode json(String text) {
        try {
            return InferenceJson.MAPPER.readTree(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
