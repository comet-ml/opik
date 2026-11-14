package com.comet.opik.domain.topics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.v1.jobs.TopicsNightlyJob;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
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
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.domain.topics.TopicsTestData.json;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The topics nightly job against real ClickHouse, MySQL and Redis, with opik-inference's {@code /v1/generate} stubbed
 * in WireMock. Each test builds a project's first list through the first-build path, a run by hand for the fixture's
 * night, then runs the next night with {@link TopicsNightlyService#runNight(Instant)}. A planted theme comes from
 * {@link TopicsNightlyFixture}. The scheduled job fires at 02:00 UTC only.
 */
@DisplayName("topics nightly job")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsNightlyJobTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final TopicsNightlyFixture NIGHTLY = TopicsNightlyFixture.load();
    private static final int BUILT = 120;
    private static final int PLANTED = 60;
    /** The product's cap of live topics, written out so that a change to the code's cap fails the test. */
    private static final int CAP = 10;
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final JsonNode NAMED = named();
    /** Text only the first naming prompt has. */
    private static final String NAMING_PROMPT = "Decide what topic each one gets.";
    /** Text only the verify prompt has. */
    private static final String VERIFY_PROMPT = "You check decisions";
    private static final String NEW_THEME = "Tire Pressure Checks";

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
    private TopicsFirstBuildService firstBuildService;
    private TopicsProjectRunner runner;
    private TopicsTestData data;
    private TopicVectorsDAO vectorsDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicWindowDAO windowDAO;
    private TopicAlgorithm algorithm;
    private GuiceJobManager jobManager;
    private ClientSupport client;
    private String baseUrl;

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, GuiceJobManager jobManager,
            @Jit TopicsNightlyService nightlyService, @Jit TopicsFirstBuildService firstBuildService,
            @Jit TopicsProjectRunner runner, @Jit TopicVectorsDAO vectorsDAO, @Jit TraceTopicsDAO traceTopicsDAO,
            @Jit TopicWindowDAO windowDAO, @Jit TopicAlgorithm algorithm) {
        this.windowDAO = windowDAO;
        this.algorithm = algorithm;
        this.client = client;
        this.opikConfig = opikConfig;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.template = template;
        this.clickHouseTemplate = clickHouseTemplate;
        this.jobManager = jobManager;
        this.nightlyService = nightlyService;
        this.firstBuildService = firstBuildService;
        this.runner = runner;
        this.vectorsDAO = vectorsDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, new TopicsTestData.Traces(
                FIXTURE.traceIds, FIXTURE.createdAt, FIXTURE.intents, FIXTURE.vectors, FIXTURE.night));
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
    @DisplayName("an update births the planted theme through the naming call, keeps the list's topics, and labels "
            + "every trace of the window with the live topics or Other")
    void nightlyUpdate() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        assertArrivalEqualsTheRun(workspaceId, projectId, firstBuildRun(projectId).runId());
        var before = data.topics(projectId);
        plant(workspaceId, projectId);
        stubNightly(NAMED, sameAnswers());

        var result = runNight(secondStart());

        assertThat(result.saved()).isEqualTo(1);
        var update = nightlyRun(projectId);
        var stats = json(update.stats());
        assertThat(stats.path("candidates").asInt()).isPositive();
        assertThat(stats.path("big").asInt()).isPositive();
        assertThat(stats.path("forced").path("born").asInt()).isPositive();
        assertThat(stats.path("born").asInt()).isPositive();
        assertThat(stats.path("churn").isNumber()).isTrue();
        assertThat(stats.path("qwen_calls").asInt()).isEqualTo(1);
        // The service decodes the keys in schema order: assign first in the naming answer.
        var bodies = wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH))).stream()
                .map(request -> request.getBodyAsString()).toList();
        assertThat(bodies).singleElement().satisfies(body -> assertThat(body).contains(NAMING_PROMPT)
                .contains("\"request_id\":\"" + update.runId() + ":naming\"")
                .contains("\"schema\":{\"type\":\"object\",\"properties\":{\"assign\":"));

        var live = data.topics(projectId);
        assertThat(live).extracting(Topic::id).containsAll(before.stream().map(Topic::id).toList());
        assertThat(live).hasSize(before.size() + 1).hasSizeLessThanOrEqualTo(CAP);
        assertThat(live).filteredOn(topic -> topic.name().equals(NEW_THEME)).singleElement()
                .satisfies(topic -> {
                    assertThat(topic.born()).isEqualTo(secondNight());
                    assertThat(topic.size()).isPositive();
                });
        var liveIds = live.stream().map(Topic::id).collect(Collectors.toSet());

        var prototypes = data.prototypes(projectId);
        assertThat(prototypes).extracting(TopicPrototype::position)
                .containsExactlyElementsOf(IntStream.range(0, prototypes.size()).boxed().toList());
        assertThat(prototypes).allSatisfy(prototype -> assertThat(
                prototype.topicId() == null || liveIds.contains(prototype.topicId())).isTrue());

        var labels = labels(workspaceId, projectId, update.runId());
        assertThat(labels).hasSize(stats.path("vectors").asInt())
                .allSatisfy(row -> assertThat(row.topicId().equals(TraceTopic.OTHER)
                        || liveIds.contains(row.topicId())).isTrue());
        live.forEach(topic -> assertThat(labels.stream().filter(row -> row.topicId().equals(topic.id())))
                .hasSize(topic.size()));
        assertThat(data.state(projectId)).satisfies(state -> {
            assertThat(state.rebuilds()).isEqualTo(2);
            assertThat(state.lastRunId()).isEqualTo(update.runId());
            assertThat(state.labelsComplete()).isTrue();
        });
        assertArrivalEqualsTheRun(workspaceId, projectId, update.runId());
    }

    @Test
    @DisplayName("a window over maxVectors: the night saves its sample's labels, then its labelling pass gives every "
            + "trace of the window a row of the run, and marks the labels complete")
    void nightOnASample() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        plant(workspaceId, projectId);
        stubNightly(NAMED, sameAnswers());
        var build = opikConfig.getTopics().getBuild();
        int maxVectors = build.getMaxVectors();
        build.setMaxVectors(100);
        TopicsNightlyService.NightResult result;
        try {
            result = runNight(secondStart());
        } finally {
            build.setMaxVectors(maxVectors);
        }

        assertThat(result.saved()).isEqualTo(1);
        var update = nightlyRun(projectId);
        var stats = json(update.stats());
        int windowTraces = stats.path("window_traces").asInt();
        assertThat(windowTraces).isGreaterThan(100);
        assertThat(stats.path("sample_traces").asInt()).isEqualTo(100);
        assertThat(labels(workspaceId, projectId, update.runId())).hasSize(windowTraces);
        assertThat(data.state(projectId).labelsComplete()).isTrue();
    }

    @Test
    @DisplayName("a failing verify call fails the run and leaves the previous night's list")
    void verifyFails() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var topics = data.topics(projectId);
        plant(workspaceId, projectId);
        var naming = InferenceJson.MAPPER.createObjectNode();
        var assign = naming.putArray("assign");
        for (int j = 0; j < 20; j++) {
            assign.addObject().put("cluster", j).put("topic", 1);
        }
        naming.putArray("new");
        naming.putArray("other");
        stubNightly(naming, null);

        var result = runNight(secondStart());

        // Read as "no pair verified", a failed verify would turn the naming's assigns into new topics.
        assertThat(result.failed()).isEqualTo(1);
        var update = nightlyRun(projectId);
        assertThat(update.status()).isEqualTo(TopicRun.Status.FAILED);
        assertThat(update.errorCode()).isEqualTo("qwen_error");
        assertThat(data.topics(projectId)).isEqualTo(topics);
        assertThat(data.state(projectId).rebuilds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing naming call fails the run and leaves the previous night's list, prototypes and labels")
    void namingFails() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var topics = data.topics(projectId);
        var prototypes = data.prototypes(projectId);
        var labels = data.traceTopics(workspaceId, projectId);
        plant(workspaceId, projectId);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)));

        var result = runNight(secondStart());

        assertThat(result.failed()).isEqualTo(1);
        var update = nightlyRun(projectId);
        assertThat(update.status()).isEqualTo(TopicRun.Status.FAILED);
        assertThat(update.errorCode()).isEqualTo("qwen_error");
        assertThat(data.topics(projectId)).isEqualTo(topics);
        assertThat(data.prototypes(projectId))
                .extracting(TopicPrototype::position, TopicPrototype::topicId, TopicPrototype::vector)
                .containsExactlyElementsOf(prototypes.stream()
                        .map(prototype -> tuple(prototype.position(), prototype.topicId(), prototype.vector()))
                        .toList());
        assertThat(data.state(projectId).rebuilds()).isEqualTo(1);
        assertThat(data.traceTopics(workspaceId, projectId)).containsExactlyInAnyOrderElementsOf(labels);
    }

    @Test
    @DisplayName("a naming answer that is not a JSON object leaves every group in Other and saves")
    void namingAnswerWithoutObject() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var before = data.topics(projectId);
        plant(workspaceId, projectId);
        stubNightly(TextNode.valueOf("Sorry, I cannot name these groups."), sameAnswers());

        assertThat(runNight(secondStart()).saved()).isEqualTo(1);

        var stats = json(nightlyRun(projectId).stats());
        assertThat(stats.path("born").asInt()).isZero();
        assertThat(stats.path("forced").path("born").asInt()).isZero();
        assertThat(stats.path("qwen_calls").asInt()).isEqualTo(1);
        assertThat(data.topics(projectId)).extracting(Topic::id).containsExactlyElementsOf(
                before.stream().map(Topic::id).toList());
    }

    @Test
    @DisplayName("with DEBUG on, a nightly update logs no trace text and no topic name: its prompts show as size and "
            + "hash")
    void nightlyLogsNoTraceText() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        plant(workspaceId, projectId);
        stubNightly(NAMED, sameAnswers());
        var logger = (Logger) LoggerFactory.getLogger(TopicsRebuildService.class);
        var level = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            assertThat(runNight(secondStart()).saved()).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(level);
        }

        var texts = Arrays.asList(NIGHTLY.intents);
        var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).filteredOn(message -> message.contains("prompt, call 'naming'")).singleElement()
                .satisfies(message -> assertThat(message).containsPattern("chars '\\d+', sha256 '[0-9a-f]{64}'"));
        assertThat(messages).noneMatch(message -> message.contains(NEW_THEME))
                .noneMatch(message -> message.contains(NAMING_PROMPT))
                .noneMatch(message -> texts.stream().anyMatch(message::contains));
    }

    @Test
    @DisplayName("when a retired topic's traffic returns, a new topic is born with a new id; the retired topic stays "
            + "retired and the save leaves none of its prototypes")
    void retiredTrafficReturns() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        // A tire topic was retired for low share the night before, with a prototype row left from an earlier save.
        var retired = Topic.builder()
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
        int position = data.prototypes(projectId).size();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicDAO.class);
            dao.insertTopics(List.of(retired));
            dao.insertPrototypes(List.of(TopicPrototype.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .position(position)
                    .topicId(retired.id())
                    .vector(TopicPrototype.encode(tireCentre()))
                    .build()));
            return null;
        });
        plant(workspaceId, projectId);
        stubNightly(NAMED, sameAnswers());

        assertThat(runNight(secondStart()).saved()).isEqualTo(1);

        assertThat(data.topics(projectId)).filteredOn(topic -> topic.name().equals(NEW_THEME))
                .singleElement().satisfies(topic -> {
                    assertThat(topic.id()).isNotEqualTo(retired.id());
                    assertThat(topic.born()).isEqualTo(secondNight());
                });
        assertThat(allTopics(projectId)).filteredOn(topic -> topic.id().equals(retired.id())).singleElement()
                .satisfies(topic -> {
                    assertThat(topic.retiredAt()).isEqualTo(secondNight().minusDays(1));
                    assertThat(topic.retiredReason()).isEqualTo("low_share");
                });
        assertThat(data.prototypes(projectId)).extracting(TopicPrototype::topicId).doesNotContain(retired.id());
        assertThat(json(nightlyRun(projectId).stats()).path("born").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a nightly update that retires every topic deletes the list's state and prototypes and writes no "
            + "labels; the project has no list, so the first-build path builds it again")
    void allRetiredIsBuiltAgain() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var firstRun = data.state(projectId).lastRunId();
        // The topics keep no prototype, so they hold no trace tonight and retire; the naming call births nothing.
        template.inTransaction(WRITE, handle -> handle.execute(
                "DELETE FROM topic_prototypes WHERE project_id = ?", projectId.toString()));
        stubAnswer(emptyNaming(), null, 0);

        assertThat(runNight(secondStart()).saved()).isEqualTo(1);

        var update = nightlyRun(projectId);
        assertThat(update.status()).isEqualTo(TopicRun.Status.SAVED);
        assertThat(data.state(projectId)).isNull();
        assertThat(data.prototypes(projectId)).isEmpty();
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(allTopics(projectId)).isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.retiredAt()).isEqualTo(secondNight()));
        assertThat(labels(workspaceId, projectId, update.runId())).isEmpty();
        assertThat(data.traceTopics(workspaceId, projectId))
                .allSatisfy(row -> assertThat(row.runId()).isEqualTo(firstRun.toString()));

        wireMock.resetAll();
        stubAnswer(FIXTURE.answer, null, 0);
        var rebuilt = buildByHand(workspaceId, projectId, secondNight());

        assertThat(data.state(projectId).lastRunId()).isEqualTo(rebuilt);
        assertThat(data.topics(projectId)).isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.born()).isEqualTo(secondNight()));
    }

    @Test
    @DisplayName("a topic's low-share streak is saved and read back over three nights: it retires on the third, and "
            + "the save leaves none of its prototypes")
    void threeNights() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        // The fixture's traces are 21 to 29 days old on its night: moved 3 days later, the first build's window and
        // the three nights' windows hold all of them.
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), FIXTURE.night.plus(Duration.ofDays(3)));
        stubAnswer(FIXTURE.answer, null, 0);
        buildByHand(workspaceId, projectId, night());
        wireMock.resetAll();
        // A tire topic with one trace in a window of more than 200: a share under 0.5% each night.
        var tire = Topic.builder()
                .id(ID_GENERATOR.generateId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name("Tire Questions")
                .definition("Questions about the car's tires.")
                .born(night())
                .lowNights(0)
                .build();
        int position = data.prototypes(projectId).size();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicDAO.class);
            dao.insertTopics(List.of(tire));
            dao.insertPrototypes(List.of(TopicPrototype.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .position(position)
                    .topicId(tire.id())
                    .vector(TopicPrototype.encode(tireCentre()))
                    .build()));
            return null;
        });
        plant(workspaceId, projectId, nearestToTireCentre(), 1);
        stubNightly(emptyNaming(), sameAnswers());

        for (int night = 1; night <= 3; night++) {
            assertThat(runNight(secondStart().plus(Duration.ofDays(night - 1))).saved()).isEqualTo(1);
            var saved = allTopics(projectId).stream().filter(topic -> topic.id().equals(tire.id())).findFirst()
                    .orElseThrow();
            assertThat(saved.lowNights()).as("night %d", night).isEqualTo(night);
            if (night < 3) {
                assertThat(saved.retiredAt()).as("night %d", night).isNull();
            } else {
                assertThat(saved.retiredAt()).isEqualTo(secondNight().plusDays(2));
                assertThat(saved.retiredReason()).isEqualTo("low_share");
            }
        }
        assertThat(data.prototypes(projectId)).extracting(TopicPrototype::topicId).doesNotContain(tire.id());
        assertThat(data.state(projectId).rebuilds()).isEqualTo(4);
    }

    @Test
    @DisplayName("a night's run whose trace_topics rows are lost and whose labelling pass did not finish is finished by "
            + "the first-build job's resume: the run's rows are written and its labels marked complete")
    void nightAfterLostInsert() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        stubNightly(emptyNaming(), sameAnswers());
        assertThat(runNight(secondStart()).saved()).isEqualTo(1);
        var lost = nightlyRun(projectId);
        // As after an insert out of retries and a pass cut by its deadline: no row of the run, labels not complete.
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement(
                "ALTER TABLE trace_topics DELETE WHERE run_id = '%s' SETTINGS mutations_sync = 2"
                        .formatted(lost.runId()))
                .execute()).flatMap(result -> Mono.from(result.getRowsUpdated()))).block();
        template.inTransaction(WRITE, handle -> handle.execute(
                "UPDATE topic_project_states SET labels_complete = FALSE WHERE project_id = ?", projectId.toString()));
        assertThat(labels(workspaceId, projectId, lost.runId())).isEmpty();

        firstBuildService.run(Instant.now());

        var state = data.state(projectId);
        assertThat(state.lastRunId()).isEqualTo(lost.runId());
        assertThat(state.labelsComplete()).isTrue();
        assertThat(labels(workspaceId, projectId, lost.runId()))
                .hasSize(json(lost.stats()).path("window_traces").asInt());
    }

    @Test
    @DisplayName("after a nightly update that retires a topic, the labelling pass also labels a trace vectored after the "
            + "window's end and before the save: its arrival row of the retired topic gets a row of the night's run")
    void passLabelsUpToTheSave() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var firstRun = data.state(projectId).lastRunId();
        // One topic keeps no prototype, so it holds no trace tonight and retires.
        var retiring = data.topics(projectId).getFirst();
        template.inTransaction(WRITE, handle -> handle.execute("DELETE FROM topic_prototypes WHERE topic_id = ?",
                retiring.id().toString()));
        // A trace created an hour after the night's window ends, with a vector and the arrival row the old list gave.
        int d = TopicsNightlyFixture.DIMENSIONS;
        var vector = new ArrayList<Float>(d);
        for (int t = 0; t < d; t++) {
            vector.add(NIGHTLY.vectors[t]);
        }
        var traceId = ID_GENERATOR.generateId();
        var createdAt = secondNight().atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(1));
        vectorsDAO.insert(List.of(TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(createdAt)
                .taskVersion(1)
                .embedModel(TopicVectorsTickService.EMBED_MODEL)
                .embedDimensions(TopicVectorsTickService.EMBED_DIMENSIONS)
                .text(NIGHTLY.intents[0])
                .vector(vector)
                .build())).block();
        traceTopicsDAO.insert(List.of(TraceTopic.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(createdAt)
                .topicId(retiring.id())
                .source(TraceTopic.Source.ARRIVAL)
                .runId(firstRun.toString())
                .build())).block();
        stubNightly(emptyNaming(), sameAnswers());

        assertThat(runNight(secondStart()).saved()).isEqualTo(1);

        var update = nightlyRun(projectId);
        assertThat(allTopics(projectId)).filteredOn(topic -> topic.id().equals(retiring.id())).singleElement()
                .satisfies(topic -> assertThat(topic.retiredAt()).isEqualTo(secondNight()));
        assertThat(data.traceTopics(workspaceId, projectId)).filteredOn(row -> row.traceId().equals(traceId))
                .singleElement().satisfies(row -> {
                    assertThat(row.runId()).isEqualTo(update.runId().toString());
                    assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
                    assertThat(row.topicId()).isNotEqualTo(retiring.id());
                });
        assertThat(data.state(projectId).labelsComplete()).isTrue();
    }

    @Test
    @DisplayName("a project without a list is not the night's: no run, no night row")
    void noListSkip() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);

        runNight(secondStart());

        assertThat(data.runs(projectId)).isEmpty();
        assertThat(nightRows(secondNight(), workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("a nightly update whose project another run saved meanwhile fails with save and changes nothing")
    void nightlyUpdateSavedMeanwhile() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var topics = data.topics(projectId);
        var prototypes = data.prototypes(projectId);
        var labels = data.traceTopics(workspaceId, projectId);
        plant(workspaceId, projectId);
        stubAnswer(NAMED, NAMING_PROMPT, 3_000);
        stubAnswer(sameAnswers(), VERIFY_PROMPT, 0);

        var night = CompletableFuture.supplyAsync(() -> nightlyService.runNight(secondStart()));
        // While Qwen names the groups, another run (its Redis lock lost) saves the project.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1));
        var other = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> handle.execute(
                "UPDATE topic_project_states SET last_run_id = ? WHERE project_id = ?", other.toString(),
                projectId.toString()));

        assertThat(night.join().failed()).isEqualTo(1);
        assertThat(nightlyRun(projectId)).satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
            assertThat(run.errorCode()).isEqualTo("save");
            assertThat(run.errorMessage()).contains("another run saved this project meanwhile");
        });
        assertThat(data.topics(projectId)).isEqualTo(topics);
        assertThat(data.prototypes(projectId))
                .extracting(TopicPrototype::position, TopicPrototype::topicId, TopicPrototype::vector)
                .containsExactlyElementsOf(prototypes.stream()
                        .map(prototype -> tuple(prototype.position(), prototype.topicId(), prototype.vector()))
                        .toList());
        assertThat(data.state(projectId).lastRunId()).isEqualTo(other);
        assertThat(data.state(projectId).rebuilds()).isEqualTo(1);
        assertThat(data.traceTopics(workspaceId, projectId)).containsExactlyInAnyOrderElementsOf(labels);
    }

    @Test
    @DisplayName("a run that outlasts projectTimeout fails with timeout and keeps the previous list; the night's next "
            + "project still runs")
    void projectTimeout() {
        var workspaceId = enabledWorkspace();
        var first = firstBuilt(workspaceId);
        var second = firstBuilt(workspaceId);
        plant(workspaceId, first);
        plant(workspaceId, second);
        // The night's first Qwen call answers after the project timeout; every later call answers at once.
        stubAnswerInScenario(NAMED, 4_000, Scenario.STARTED, "answered");
        stubAnswerInScenario(NAMED, 0, "answered", "answered");
        var build = opikConfig.getTopics().getBuild();
        var projectTimeout = build.getProjectTimeout();
        build.setProjectTimeout(io.dropwizard.util.Duration.seconds(2));
        TopicsNightlyService.NightResult result;
        try {
            result = runNight(secondStart());
        } finally {
            build.setProjectTimeout(projectTimeout);
        }

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        var timedOut = nightlyRun(first).status() == TopicRun.Status.FAILED ? first : second;
        var saved = timedOut.equals(first) ? second : first;
        assertThat(nightlyRun(saved).status()).isEqualTo(TopicRun.Status.SAVED);
        assertThat(data.state(saved).rebuilds()).isEqualTo(2);
        var topics = data.topics(timedOut);
        // The interrupted update may still get its slow answer: past it, a save that raced the timeout would show.
        Awaitility.await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(nightlyRun(timedOut)).satisfies(run -> {
                assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
                assertThat(run.errorCode()).isEqualTo("timeout");
                assertThat(run.errorMessage()).contains("projectTimeout");
            });
            assertThat(data.topics(timedOut)).isEqualTo(topics);
            assertThat(data.state(timedOut).rebuilds()).isEqualTo(1);
            assertThat(labels(workspaceId, timedOut, nightlyRun(timedOut).runId())).isEmpty();
        });
    }

    @Test
    @DisplayName("the night endpoint runs the whole night now and updates the project with a list; a night that is "
            + "not a date is refused")
    void runNightByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        stubAnswer(emptyNaming(), null, 0);

        try (var response = runNightEndpoint(secondNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.readEntity(JsonNode.class).path("night").asText())
                    .isEqualTo(secondNight().toString());
        }
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(nightlyRun(projectId)
                .status()).isEqualTo(TopicRun.Status.SAVED));
        // Released when the night ended: a second night can start.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (var response = runNightEndpoint(secondNight().toString())) {
                assertThat(response.getStatus()).isEqualTo(202);
            }
        });
        try (var response = runNightEndpoint("tomorrow")) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("a project with a list in a workspace opted in to ai-labels but not to topics gets no nightly update")
    void topicsOptIn() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        data.savedList(workspaceId, projectId, FIXTURE.night, BUILT);

        runNight(secondStart());

        assertThat(data.runs(projectId)).noneMatch(run -> run.mode() == TopicRun.Mode.NIGHTLY_UPDATE);
        assertThat(nightRows(secondNight(), workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("a project whose last 3 runs failed is skipped as paused; one with 2 failed runs is updated")
    void pauseAfterThreeFailures() {
        var workspaceId = enabledWorkspace();
        var paused = firstBuilt(workspaceId);
        var twoFailures = firstBuilt(workspaceId);
        var now = Instant.now();
        for (int i = 1; i <= 3; i++) {
            insertFailedRun(workspaceId, paused, now.plusSeconds(i));
        }
        for (int i = 1; i <= 2; i++) {
            insertFailedRun(workspaceId, twoFailures, now.plusSeconds(i));
        }
        stubAnswer(emptyNaming(), null, 0);

        var result = runNight(secondStart());

        assertThat(result.saved()).isEqualTo(1);
        assertThat(nightRows(secondNight(), workspaceId))
                .extracting(TopicNightProject::projectId, TopicNightProject::decision, TopicNightProject::reason)
                .containsExactlyInAnyOrder(
                        tuple(paused, "skip", "paused"),
                        tuple(twoFailures, "nightly_update", null));
        assertThat(data.runs(paused)).hasSize(4);
        assertThat(nightlyRun(twoFailures).status()).isEqualTo(TopicRun.Status.SAVED);
    }

    /**
     * A project with a first list built by hand for the fixture's night, its labelling pass done, and its locks
     * released.
     */
    private UUID firstBuilt(String workspaceId) {
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(FIXTURE.answer, null, 0);
        buildByHand(workspaceId, projectId, night());
        assertThat(data.state(projectId).labelsComplete()).isTrue();
        wireMock.resetAll();
        return projectId;
    }

    /**
     * Builds the project's first list by hand for {@code night} and returns its run, once saved and its locks are
     * released. A run by hand of an earlier test may still hold the by-hand lock for a moment: the call is retried.
     */
    private UUID buildByHand(String workspaceId, UUID projectId, LocalDate night) {
        var runId = new AtomicReference<UUID>();
        Awaitility.await().atMost(Duration.ofSeconds(60)).ignoreExceptions()
                .untilAsserted(() -> runId.set(firstBuildService.runNow(workspaceId, projectId, night, false)));
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(data.runs(projectId)).filteredOn(run -> run.runId().equals(runId.get())).singleElement()
                    .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED));
            assertThat(runner.lockProject(projectId)).isTrue();
        });
        runner.unlockProject(projectId);
        return runId.get();
    }

    /**
     * Runs the night until no project of it is skipped as locked: a run by hand of this or an earlier test may still
     * hold a project's lock for a moment. A rerun of the same night replaces its record.
     */
    private TopicsNightlyService.NightResult runNight(Instant start) {
        var result = new AtomicReference<TopicsNightlyService.NightResult>();
        var night = LocalDate.ofInstant(start, ZoneOffset.UTC);
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            result.set(nightlyService.runNight(start));
            assertThat(nightRows(night, null)).noneMatch(row -> TopicNightProject.REASON_LOCKED.equals(row.reason()));
        });
        return result.get();
    }

    /** The night's project rows, of one workspace or all when {@code workspaceId} is null. */
    private List<TopicNightProject> nightRows(LocalDate night, String workspaceId) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicNightDAO.class).findProjects(night))
                .stream().filter(row -> workspaceId == null || workspaceId.equals(row.workspaceId())).toList();
    }

    /** The trace_topics rows the run wrote. */
    private List<TraceTopic> labels(String workspaceId, UUID projectId, UUID runId) {
        return data.traceTopics(workspaceId, projectId).stream()
                .filter(row -> row.runId().equals(runId.toString())).toList();
    }

    /** The {@value #PLANTED} traces of the nightly fixture's theme, created the day before the second night. */
    private void plant(String workspaceId, UUID projectId) {
        plant(workspaceId, projectId, 0, PLANTED);
    }

    /** The nightly fixture's traces {@code [from, from + count)}, created the day before the second night. */
    private void plant(String workspaceId, UUID projectId, int from, int count) {
        int d = TopicsNightlyFixture.DIMENSIONS;
        var day = secondNight().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var rows = new ArrayList<TopicVector>(count);
        for (int i = from; i < from + count; i++) {
            var vector = new ArrayList<Float>(d);
            for (int t = 0; t < d; t++) {
                vector.add(NIGHTLY.vectors[i * d + t]);
            }
            rows.add(TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .traceId(ID_GENERATOR.generateId())
                    .traceCreatedAt(day.plus(Duration.ofMinutes(i)))
                    .taskVersion(1)
                    .embedModel(TopicVectorsTickService.EMBED_MODEL)
                    .embedDimensions(TopicVectorsTickService.EMBED_DIMENSIONS)
                    .text(NIGHTLY.intents[i])
                    .vector(vector)
                    .build());
        }
        vectorsDAO.insert(rows).block();
    }

    /** The nightly fixture's trace nearest to {@link #tireCentre()}. */
    private static int nearestToTireCentre() {
        int d = TopicsNightlyFixture.DIMENSIONS;
        var centre = tireCentre();
        int best = 0;
        double bestDot = -2;
        for (int i = 0; i < PLANTED; i++) {
            double dot = 0;
            for (int t = 0; t < d; t++) {
                dot += (double) NIGHTLY.vectors[i * d + t] * centre[t];
            }
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return best;
    }

    /** The unit mean of the {@value #PLANTED} vectors of the nightly fixture. */
    private static float[] tireCentre() {
        int d = TopicsNightlyFixture.DIMENSIONS;
        var sum = new double[d];
        for (int i = 0; i < PLANTED; i++) {
            for (int t = 0; t < d; t++) {
                sum[t] += NIGHTLY.vectors[i * d + t];
            }
        }
        double norm = Math.sqrt(Arrays.stream(sum).map(value -> value * value).sum());
        var out = new float[d];
        for (int t = 0; t < d; t++) {
            out[t] = (float) (sum[t] / norm);
        }
        return out;
    }

    /** Stubs the nightly update's calls by prompt: the first naming call, and the verify (a 500 when null). */
    private void stubNightly(JsonNode naming, JsonNode verify) {
        stubAnswer(naming, NAMING_PROMPT, 0);
        if (verify == null) {
            wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).withRequestBody(containing(VERIFY_PROMPT))
                    .willReturn(aResponse().withStatus(500)));
        } else {
            stubAnswer(verify, VERIFY_PROMPT, 0);
        }
    }

    /** A naming answer that places nothing: every candidate stays in Other. */
    private static JsonNode emptyNaming() {
        var naming = InferenceJson.MAPPER.createObjectNode();
        naming.putArray("assign");
        naming.putArray("new");
        naming.putArray("other");
        return naming;
    }

    private static JsonNode sameAnswers() {
        var answer = InferenceJson.MAPPER.createObjectNode();
        var answers = answer.putArray("answers");
        for (int pair = 0; pair < 20; pair++) {
            answers.addObject().put("pair", pair).put("same", true);
        }
        return answer;
    }

    private static JsonNode named() {
        var answer = InferenceJson.MAPPER.createObjectNode();
        answer.putArray("assign");
        // Every group id the night can have: the answer keeps only the forced ones, as one new topic.
        var clusters = answer.putArray("new").addObject().put("name", NEW_THEME)
                .put("definition", "Asks whether the car's tires need air.").putArray("clusters");
        for (int j = 0; j < 100; j++) {
            clusters.add(j);
        }
        answer.putArray("other");
        return answer;
    }

    private TopicRun firstBuildRun(UUID projectId) {
        return data.runs(projectId).stream().filter(run -> run.mode() == TopicRun.Mode.FIRST_BUILD).findFirst()
                .orElseThrow();
    }

    /**
     * What topic on arrival relies on: the bound algorithm's labeller, made from the prototypes the run saved in MySQL,
     * gives every trace the run labelled the label the run wrote to {@code trace_topics}.
     */
    private void assertArrivalEqualsTheRun(String workspaceId, UUID projectId, UUID runId) {
        var labels = labels(workspaceId, projectId, runId).stream()
                .collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId));
        // Wider than any window of these tests: only the run's traces are compared.
        var from = night().minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant();
        var to = secondNight().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var window = windowDAO.readWindow(workspaceId, projectId, from, to, 100_000, 120).collectList().block()
                .stream().filter(row -> labels.containsKey(row.traceId())).toList();
        assertThat(window).hasSize(labels.size()).isNotEmpty();

        int dimensions = TopicsRebuildService.DIMENSIONS;
        var vectors = new float[window.size() * dimensions];
        for (int i = 0; i < window.size(); i++) {
            System.arraycopy(window.get(i).vector(), 0, vectors, i * dimensions, dimensions);
        }
        var arrived = algorithm.labeller(data.prototypes(projectId)).orElseThrow().label(vectors);
        for (int i = 0; i < window.size(); i++) {
            assertThat(arrived[i]).as("trace '%s'", window.get(i).traceId())
                    .isEqualTo(labels.get(window.get(i).traceId()));
        }
    }

    private TopicRun nightlyRun(UUID projectId) {
        return data.runs(projectId).stream().filter(run -> run.mode() == TopicRun.Mode.NIGHTLY_UPDATE).findFirst()
                .orElseThrow();
    }

    private List<Topic> allTopics(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> handle.createQuery(
                "SELECT * FROM topics WHERE project_id = :projectId").bind("projectId", projectId.toString())
                .map(ConstructorMapper.of(Topic.class)).list());
    }

    /** A failed nightly update of the project, started at {@code startedAt}. */
    private void insertFailedRun(String workspaceId, UUID projectId, Instant startedAt) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder().runId(UUID.randomUUID())
                    .workspaceId(workspaceId).projectId(projectId).mode(TopicRun.Mode.NIGHTLY_UPDATE)
                    .status(TopicRun.Status.FAILED).errorCode("timeout").startedAt(startedAt)
                    .finishedAt(startedAt.plusSeconds(1)).build());
            return null;
        });
    }

    /** The fixture's night: its window holds all of the fixture's traces. */
    private static LocalDate night() {
        return LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);
    }

    private static LocalDate secondNight() {
        return night().plusDays(1);
    }

    private static Instant secondStart() {
        return secondNight().atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(2));
    }

    /**
     * Opted in to both, and seen by the vectors tick with no backlog: one drained backfill row, of another project,
     * so the first build of a project with no row of its own does not wait.
     */
    private String enabledWorkspace() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var topics = opikConfig.getTopics();
        var listed = new TreeSet<>(topics.enabledWorkspaceIds());
        listed.add(workspaceId);
        topics.setEnabledWorkspaces(String.join(",", listed));
        var now = Instant.now();
        var other = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicVectorBackfillDAO.class);
            dao.start(List.of(TopicVectorBackfill.builder().workspaceId(workspaceId).projectId(other)
                    .startedAt(now).checkedAt(now).lastDoneAt(now).build()));
            dao.record(List.of(TopicVectorBackfill.Progress.builder().workspaceId(workspaceId).projectId(other)
                    .drainedAt(now).build()));
            return null;
        });
        return workspaceId;
    }

    /** Opted in to ai-labels only, not listed in topics.enabledWorkspaces. */
    private String aiLabelsOnlyWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        return workspaceId;
    }

    private Response runNightEndpoint(String night) {
        return client.target("%s/v1/internal/topics/nights/run".formatted(baseUrl))
                .queryParam("night", night)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    /** Answers {@code output} after {@code delayMillis} to the calls whose body contains {@code prompt}, or all. */
    private void stubAnswer(JsonNode output, String prompt, int delayMillis) {
        var request = post(urlEqualTo(GENERATE_PATH));
        if (prompt != null) {
            request = request.withRequestBody(containing(prompt));
        }
        wireMock.stubFor(request.willReturn(TopicsTestData.answer(output, delayMillis)));
    }

    /** {@code output} after {@code delayMillis}, matched only in the scenario's {@code state}, which it then moves on. */
    private void stubAnswerInScenario(JsonNode output, int delayMillis, String state, String nextState) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).inScenario("answers").whenScenarioStateIs(state)
                .willSetStateTo(nextState).willReturn(TopicsTestData.answer(output, delayMillis)));
    }
}
