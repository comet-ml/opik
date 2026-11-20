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
import com.comet.opik.api.resources.utils.TopicsTraces;
import com.comet.opik.api.resources.v1.jobs.TopicsNightlyJob;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.lock.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
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
 * in WireMock. Each test builds a project's first list through the first-build path, a run by hand for the fixture's
 * night, then runs the next night with {@link TopicsNightlyService#runNight(Instant)}. A planted theme comes from
 * {@link TopicsNightlyFixture}. The scheduled job fires at 02:00 UTC only. A run's stats, its retired topics and its
 * counts are read from its log lines, as an operator reads them.
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
                        new CustomConfig("inference.url", wireMock.baseUrl()),
                        new CustomConfig("inference.credential", "test-credential"),
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
    private ConnectionFactory connectionFactory;
    private GuiceJobManager jobManager;
    private LockService lockService;
    private ClientSupport client;
    private String baseUrl;
    /** The run, runner and night log lines of the test. */
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers = Stream.of(TopicsRebuildService.class, TopicsProjectRunner.class,
            TopicWindowLabelService.class, TopicsNightlyService.class)
            .map(type -> (Logger) LoggerFactory.getLogger(type)).toList();

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, GuiceJobManager jobManager, LockService lockService,
            @Jit TopicsNightlyService nightlyService, @Jit TopicsFirstBuildService firstBuildService,
            @Jit TopicsProjectRunner runner, @Jit TopicVectorsDAO vectorsDAO, @Jit TraceTopicsDAO traceTopicsDAO,
            @Jit TopicWindowDAO windowDAO, @Jit TopicAlgorithm algorithm, ConnectionFactory connectionFactory) {
        this.windowDAO = windowDAO;
        this.algorithm = algorithm;
        this.client = client;
        this.opikConfig = opikConfig;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.template = template;
        this.clickHouseTemplate = clickHouseTemplate;
        this.jobManager = jobManager;
        this.lockService = lockService;
        this.nightlyService = nightlyService;
        this.firstBuildService = firstBuildService;
        this.runner = runner;
        this.vectorsDAO = vectorsDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.connectionFactory = connectionFactory;
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
        // Each test builds its own workspaces: the earlier ones must not be eligible again.
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE");
            return null;
        });
        opikConfig.getTopics().setEnabledWorkspaces("");
        wireMock.resetAll();
        appender.list.clear();
        appender.start();
        loggers.forEach(logger -> logger.addAppender(appender));
    }

    @AfterEach
    void tearDown() {
        loggers.forEach(logger -> logger.detachAppender(appender));
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
        assertArrivalEqualsTheRun(workspaceId, projectId, data.topicProject(projectId).listRunId());
        var before = data.topics(projectId);
        plant(workspaceId, projectId);
        stubNightly(NAMED, sameAnswers());

        var result = runNight(secondStart());

        assertThat(result.saved()).isEqualTo(1);
        var update = nightlyRun(projectId);
        var stats = update.stats();
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

        var labels = labels(workspaceId, projectId, update.runId());
        assertThat(labels).hasSize(stats.path("vectors").asInt())
                .allSatisfy(row -> assertThat(row.topicId().equals(TraceTopic.OTHER)
                        || liveIds.contains(row.topicId())).isTrue());
        live.forEach(topic -> assertThat(labels.stream().filter(row -> row.topicId().equals(topic.id())))
                .hasSize(topic.size()));
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.savedRuns()).isEqualTo(2);
            assertThat(project.listRunId()).isEqualTo(update.runId());
            assertThat(project.labelsComplete()).isTrue();
            assertThat(project.lastNight()).isEqualTo(secondNight());
            assertThat(project.notReachedNights()).isZero();
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
        var stats = update.stats();
        int windowTraces = stats.path("window_traces").asInt();
        assertThat(windowTraces).isGreaterThan(100);
        assertThat(stats.path("sample_traces").asInt()).isEqualTo(100);
        assertThat(labels(workspaceId, projectId, update.runId())).hasSize(windowTraces);
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
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
        assertThat(update.status()).isEqualTo(TopicProject.RunStatus.FAILED);
        assertThat(update.errorCode()).isEqualTo("llm_error");
        assertThat(data.topics(projectId)).isEqualTo(topics);
        assertThat(data.topicProject(projectId).savedRuns()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing naming call fails the run and leaves the previous night's list, its prototypes and labels")
    void namingFails() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var topics = data.topics(projectId);
        var otherVectors = data.topicProject(projectId).otherVectors();
        var labels = data.traceTopics(workspaceId, projectId);
        plant(workspaceId, projectId);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)));

        var result = runNight(secondStart());

        assertThat(result.failed()).isEqualTo(1);
        var update = nightlyRun(projectId);
        assertThat(update.status()).isEqualTo(TopicProject.RunStatus.FAILED);
        assertThat(update.errorCode()).isEqualTo("llm_error");
        assertThat(data.topics(projectId)).isEqualTo(topics);
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.otherVectors()).isEqualTo(otherVectors);
            assertThat(project.savedRuns()).isEqualTo(1);
        });
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

        var stats = nightlyRun(projectId).stats();
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

        var texts = List.of(NIGHTLY.intents);
        var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).filteredOn(message -> message.contains("prompt, call 'naming'")).singleElement()
                .satisfies(message -> assertThat(message).containsPattern("chars '\\d+', sha256 '[0-9a-f]{64}'"));
        assertThat(messages).noneMatch(message -> message.contains(NEW_THEME))
                .noneMatch(message -> message.contains(NAMING_PROMPT))
                .noneMatch(message -> texts.stream().anyMatch(message::contains));
    }

    @Test
    @DisplayName("a nightly update that retires every topic deletes them, clears the list and writes no labels; the "
            + "project has no list, so the first-build path builds it again with new topic ids")
    void allRetiredIsBuiltAgain() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var firstRun = data.topicProject(projectId).listRunId();
        var before = data.topics(projectId);
        // The topics' prototypes point away from every trace, so they hold no trace tonight and retire; the naming
        // call births nothing.
        moveAway(before);
        stubAnswer(emptyNaming(), null, 0);

        assertThat(runNight(secondStart()).saved()).isEqualTo(1);

        var update = nightlyRun(projectId);
        assertThat(update.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(retired(update.runId())).containsOnlyKeys(before.stream().map(Topic::id).toList())
                .allSatisfy((topicId, reason) -> assertThat(reason).isEqualTo("low_share"));
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.listRunId()).isNull();
            assertThat(project.windowUntil()).isNull();
            assertThat(project.otherVectors()).isNull();
        });
        assertThat(labels(workspaceId, projectId, update.runId())).isEmpty();
        assertThat(data.traceTopics(workspaceId, projectId))
                .allSatisfy(row -> assertThat(row.runId()).isEqualTo(firstRun));

        wireMock.resetAll();
        stubAnswer(FIXTURE.answer, null, 0);
        var rebuilt = buildByHand(workspaceId, projectId, secondNight());

        assertThat(data.topicProject(projectId).listRunId()).isEqualTo(rebuilt);
        assertThat(data.topics(projectId)).isNotEmpty()
                .allSatisfy(topic -> {
                    assertThat(topic.born()).isEqualTo(secondNight());
                    assertThat(before).extracting(Topic::id).doesNotContain(topic.id());
                });
    }

    @Test
    @DisplayName("a topic's low-share streak is saved and read back over three nights: it retires on the third, its "
            + "row is deleted and its retirement logged")
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
                .vectors(new PrototypeVectors(List.of(tireCentre())))
                .build();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertTopics(List.of(tire));
            return null;
        });
        plant(workspaceId, projectId, nearestToTireCentre(), 1);
        stubNightly(emptyNaming(), sameAnswers());

        for (int night = 1; night <= 3; night++) {
            assertThat(runNight(secondStart().plus(Duration.ofDays(night - 1))).saved()).isEqualTo(1);
            var saved = data.topics(projectId).stream().filter(topic -> topic.id().equals(tire.id())).findFirst();
            if (night < 3) {
                assertThat(saved).as("night %d", night).hasValueSatisfying(
                        topic -> assertThat(topic.lowNights()).isEqualTo(data.topicProject(projectId).savedRuns() - 1));
            } else {
                assertThat(saved).isEmpty();
                assertThat(retired(nightlyRun(projectId).runId())).containsEntry(tire.id(), "low_share");
            }
        }
        assertThat(data.topicProject(projectId).savedRuns()).isEqualTo(4);
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
                "UPDATE topic_projects SET labels_complete = FALSE WHERE project_id = ?", projectId.toString()));
        assertThat(labels(workspaceId, projectId, lost.runId())).isEmpty();

        firstBuildService.run(Instant.now());

        var project = data.topicProject(projectId);
        assertThat(project.listRunId()).isEqualTo(lost.runId());
        assertThat(project.labelsComplete()).isTrue();
        assertThat(labels(workspaceId, projectId, lost.runId()))
                .hasSize(lost.stats().path("window_traces").asInt());
    }

    @Test
    @DisplayName("after a nightly update that retires a topic, the labelling pass also labels a trace vectored after the "
            + "window's end and before the save: its arrival row of the retired topic gets a row of the night's run")
    void passLabelsUpToTheSave() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var firstRun = data.topicProject(projectId).listRunId();
        // One topic's prototype points away from every trace, so it holds no trace tonight and retires.
        var retiring = data.topics(projectId).getFirst();
        moveAway(List.of(retiring));
        // A trace created an hour after the night's window ends, with a vector and the arrival row the old list gave.
        var late = vectorAfterTheWindow(workspaceId, projectId);
        var traceId = late.traceId();
        var createdAt = late.traceCreatedAt();
        traceTopicsDAO.insert(List.of(TraceTopic.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(createdAt)
                .topicId(retiring.id())
                .source(TraceTopic.Source.ARRIVAL)
                .runId(firstRun)
                .build())).block();
        stubNightly(emptyNaming(), sameAnswers());

        assertThat(runNight(secondStart()).saved()).isEqualTo(1);

        var update = nightlyRun(projectId);
        assertThat(retired(update.runId())).containsOnlyKeys(retiring.id());
        assertThat(data.topics(projectId)).extracting(Topic::id).doesNotContain(retiring.id());
        assertThat(data.traceTopics(workspaceId, projectId)).filteredOn(row -> row.traceId().equals(traceId))
                .singleElement().satisfies(row -> {
                    assertThat(row.runId()).isEqualTo(update.runId());
                    assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
                    assertThat(row.topicId()).isNotEqualTo(retiring.id());
                });
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
    }

    @Test
    @DisplayName("a nightly update whose pass completes logs its window's traces per topic in its topic_counts line: "
            + "the topic born that night counted, the topic retired that night absent, and a trace after the window's "
            + "end left out")
    void nightSavesTheWindowCounts() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        // One topic's prototype points away from every trace, so it holds no trace tonight and retires.
        var retiring = data.topics(projectId).getFirst();
        moveAway(List.of(retiring));
        var planted = plant(workspaceId, projectId);
        var late = vectorAfterTheWindow(workspaceId, projectId);
        // The counts read only the traces Logs lists: every trace gets its traces row.
        var traces = new ArrayList<TopicsTraces.TraceRow>();
        data.traceTopics(workspaceId, projectId).forEach(row -> traces.add(
                TopicsTraces.TraceRow.shown(workspaceId, projectId, row.traceId(), row.traceCreatedAt())));
        Stream.concat(planted.stream(), Stream.of(late)).forEach(vector -> traces.add(
                TopicsTraces.TraceRow.shown(workspaceId, projectId, vector.traceId(), vector.traceCreatedAt())));
        TopicsTraces.insert(connectionFactory, PostCutoverTraces.SHARD, traces);
        stubNightly(NAMED, sameAnswers());

        assertThat(runNight(secondStart()).saved()).isEqualTo(1);

        var update = nightlyRun(projectId);
        var stats = update.stats();
        var from = Instant.parse(stats.path("window_from").asText());
        var until = Instant.parse(stats.path("window_until").asText());
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
        assertThat(retired(update.runId())).containsOnlyKeys(retiring.id());
        var live = data.topics(projectId);
        var born = live.stream().filter(topic -> topic.name().equals(NEW_THEME)).findFirst().orElseThrow();
        // Each trace of the window by its latest row, as the count reads it; zero for a live topic with none.
        var expected = new HashMap<UUID, Long>();
        live.forEach(topic -> expected.put(topic.id(), 0L));
        expected.put(TraceTopic.OTHER, 0L);
        data.traceTopics(workspaceId, projectId).stream()
                .filter(row -> !row.traceCreatedAt().isBefore(from) && row.traceCreatedAt().isBefore(until))
                .forEach(row -> expected.merge(row.topicId(), 1L, Long::sum));

        var counts = counts(update.runId());
        assertThat(counts).isEqualTo(expected).doesNotContainKey(retiring.id());
        assertThat(counts.get(born.id())).isPositive();
        assertThat(counts.values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(stats.path("window_traces").asLong());
        assertThat(data.traceTopics(workspaceId, projectId)).filteredOn(row -> row.traceId().equals(late.traceId()))
                .singleElement().satisfies(row -> assertThat(row.runId()).isEqualTo(update.runId()));
    }

    @Test
    @DisplayName("a project without a list is not the night's: no attempt, and the night does not mark it")
    void noListSkip() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);

        runNight(secondStart());

        assertNotTheNights(projectId);
    }

    @Test
    @DisplayName("a nightly update whose project another run saved meanwhile fails with save and changes nothing")
    void nightlyUpdateSavedMeanwhile() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var topics = data.topics(projectId);
        var otherVectors = data.topicProject(projectId).otherVectors();
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
                "UPDATE topic_projects SET list_run_id = ? WHERE project_id = ?", other.toString(),
                projectId.toString()));

        assertThat(night.join().failed()).isEqualTo(1);
        assertThat(nightlyRun(projectId)).satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(run.errorCode()).isEqualTo("save");
            assertThat(runLog(run.runId())).anyMatch(line -> line.contains("another run saved this project meanwhile"));
        });
        assertThat(data.topics(projectId)).isEqualTo(topics);
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.otherVectors()).isEqualTo(otherVectors);
            assertThat(project.listRunId()).isEqualTo(other);
            assertThat(project.savedRuns()).isEqualTo(1);
        });
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
        var timedOut = nightlyRun(first).status() == TopicProject.RunStatus.FAILED ? first : second;
        var saved = timedOut.equals(first) ? second : first;
        assertThat(nightlyRun(saved).status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topicProject(saved).savedRuns()).isEqualTo(2);
        var topics = data.topics(timedOut);
        // The interrupted update may still get its slow answer: past it, a save that raced the timeout would show.
        Awaitility.await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(nightlyRun(timedOut)).satisfies(run -> {
                assertThat(run.status()).isEqualTo(TopicProject.RunStatus.FAILED);
                assertThat(run.errorCode()).isEqualTo("timeout");
                assertThat(runLog(run.runId())).anyMatch(line -> line.contains("projectTimeout"));
            });
            assertThat(data.topics(timedOut)).isEqualTo(topics);
            assertThat(data.topicProject(timedOut).savedRuns()).isEqualTo(1);
            assertThat(labels(workspaceId, timedOut, nightlyRun(timedOut).runId())).isEmpty();
        });
    }

    @Test
    @DisplayName("the night endpoint runs the whole night now and updates the project with a list, and marks the date "
            + "as run so the scheduled job skips it; a night that is not a date is refused")
    void runNightByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        stubAnswer(emptyNaming(), null, 0);

        try (var response = runNightEndpoint(secondNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.readEntity(JsonNode.class).path("night").asText())
                    .isEqualTo(secondNight().toString());
        }
        // The night runs in the background: its attempt may not have started yet at the first poll.
        Awaitility.await().atMost(Duration.ofSeconds(60)).ignoreExceptions().untilAsserted(
                () -> assertThat(nightlyRun(projectId).status()).isEqualTo(TopicProject.RunStatus.SAVED));
        assertThat(lockService.lockUsingToken(TopicsNightlyService.runLock(secondNight()), Duration.ofMinutes(1))
                .block()).isFalse();
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

        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.FIRST_BUILD);
            assertThat(project.lastNight()).isNull();
        });
    }

    @Test
    @DisplayName("a project whose last 3 attempts failed is skipped as paused, and the night still marks it; one with 2 "
            + "failed attempts is updated")
    void pauseAfterThreeFailures() {
        var workspaceId = enabledWorkspace();
        var paused = firstBuilt(workspaceId);
        var twoFailures = firstBuilt(workspaceId);
        var pausedRun = failedAttempts(paused, 3);
        failedAttempts(twoFailures, 2);
        stubAnswer(emptyNaming(), null, 0);

        var result = runNight(secondStart());

        assertThat(result.saved()).isEqualTo(1);
        assertThat(data.topicProject(paused)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(pausedRun);
            assertThat(project.failedRuns()).isEqualTo(3);
            assertThat(project.lastNight()).isEqualTo(secondNight());
        });
        assertThat(nightLog()).anyMatch(line -> line.contains(paused.toString()) && line.contains("reason 'paused'"));
        assertThat(nightlyRun(twoFailures).status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topicProject(twoFailures).failedRuns()).isZero();
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
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
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
            assertThat(data.topicProject(projectId)).satisfies(project -> {
                assertThat(project.runId()).isEqualTo(runId.get());
                assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
            });
            assertThat(runner.lockProject(projectId)).isTrue();
        });
        runner.unlockProject(projectId);
        return runId.get();
    }

    /**
     * Runs the night until no project of it is skipped as locked: a run by hand of this or an earlier test may still
     * hold a project's lock for a moment.
     */
    private TopicsNightlyService.NightResult runNight(Instant start) {
        var result = new AtomicReference<TopicsNightlyService.NightResult>();
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            int from = logEvents().size();
            result.set(nightlyService.runNight(start));
            assertThat(logEvents().subList(from, logEvents().size())).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(line -> line.startsWith("Topics night") && line.contains("reason 'locked'"));
        });
        return result.get();
    }

    /** The project has no nightly attempt and no night marked it. */
    private void assertNotTheNights(UUID projectId) {
        var project = data.topicProject(projectId);
        if (project != null) {
            assertThat(project.runMode()).isNotEqualTo(TopicProject.RunMode.NIGHTLY_UPDATE);
            assertThat(project.lastNight()).isNull();
        }
    }

    /** The trace_topics rows the run wrote. */
    private List<TraceTopic> labels(String workspaceId, UUID projectId, UUID runId) {
        return data.traceTopics(workspaceId, projectId).stream()
                .filter(row -> runId.equals(row.runId())).toList();
    }

    /** The {@value #PLANTED} traces of the nightly fixture's theme, created the day before the second night. */
    private List<TopicVector> plant(String workspaceId, UUID projectId) {
        return plant(workspaceId, projectId, 0, PLANTED);
    }

    /** The nightly fixture's traces {@code [from, from + count)}, created the day before the second night. */
    private List<TopicVector> plant(String workspaceId, UUID projectId, int from, int count) {
        int d = TopicsNightlyFixture.DIMENSIONS;
        var day = secondNight().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var rows = new ArrayList<TopicVector>(count);
        for (int i = from; i < from + count; i++) {
            var vector = Arrays.copyOfRange(NIGHTLY.vectors, i * d, (i + 1) * d);
            rows.add(TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .traceId(ID_GENERATOR.generateId())
                    .traceCreatedAt(day.plus(Duration.ofMinutes(i)))
                    .taskVersion(1)
                    .embedModel(TopicEmbedding.MODEL)
                    .embedDimensions(TopicEmbedding.DIMENSIONS)
                    .text(NIGHTLY.intents[i])
                    .vector(vector)
                    .build());
        }
        vectorsDAO.insert(rows).block();
        return rows;
    }

    /** The vector of a trace created an hour after the second night's window ends. */
    private TopicVector vectorAfterTheWindow(String workspaceId, UUID projectId) {
        int d = TopicsNightlyFixture.DIMENSIONS;
        var vector = Arrays.copyOf(NIGHTLY.vectors, d);
        var row = TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(ID_GENERATOR.generateId())
                .traceCreatedAt(secondNight().atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(1)))
                .taskVersion(1)
                .embedModel(TopicEmbedding.MODEL)
                .embedDimensions(TopicEmbedding.DIMENSIONS)
                .text(NIGHTLY.intents[0])
                .vector(vector)
                .build();
        vectorsDAO.insert(List.of(row)).block();
        return row;
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

    /**
     * What topic on arrival relies on: the bound algorithm's labeller, made from the list the run saved in MySQL (the
     * project's topics and its known-Other vectors), gives every trace the run labelled the label the run wrote to
     * {@code trace_topics}.
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

        int dimensions = TopicEmbedding.DIMENSIONS;
        var vectors = new float[window.size() * dimensions];
        for (int i = 0; i < window.size(); i++) {
            System.arraycopy(window.get(i).vector(), 0, vectors, i * dimensions, dimensions);
        }
        var project = data.topicProject(projectId);
        assertThat(project.listRunId()).isEqualTo(runId);
        var arrived = algorithm.labeller(data.topics(projectId), project.otherVectors()).orElseThrow()
                .label(vectors);
        for (int i = 0; i < window.size(); i++) {
            assertThat(arrived[i]).as("trace '%s'", window.get(i).traceId())
                    .isEqualTo(labels.get(window.get(i).traceId()));
        }
    }

    /** A project's nightly attempt, read from {@code topic_projects}, with the stats its log line holds. */
    private record NightlyRun(UUID runId, TopicProject.RunStatus status, String errorCode, JsonNode stats) {
    }

    private NightlyRun nightlyRun(UUID projectId) {
        var project = data.topicProject(projectId);
        assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.NIGHTLY_UPDATE);
        return new NightlyRun(project.runId(), project.runStatus(),
                project.runErrorCode() == null ? null : project.runErrorCode().getValue(), stats(project.runId()));
    }

    /** The test's captured log lines, copied under the appender's lock: runs log from their own threads. */
    private List<ILoggingEvent> logEvents() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    /** The stats of the run's {@code saved} or {@code failed} line; null when it logged none. */
    private JsonNode stats(UUID runId) {
        return logEvents().stream().filter(event -> isRun(event, runId))
                .flatMap(event -> Arrays.stream(event.getArgumentArray()))
                .filter(JsonNode.class::isInstance).map(JsonNode.class::cast)
                .reduce((first, second) -> second).orElse(null);
    }

    /** The run's retired topics, by id, with their reason, from its retire lines. */
    private Map<UUID, String> retired(UUID runId) {
        return logEvents().stream()
                .filter(event -> isRun(event, runId) && event.getMessage().contains("retired topic"))
                .collect(Collectors.toMap(event -> (UUID) event.getArgumentArray()[1],
                        event -> (String) event.getArgumentArray()[3]));
    }

    /** The run's traces per topic, from its labelling pass's {@code topic_counts} line. */
    private Map<UUID, Long> counts(UUID runId) {
        var line = logEvents().stream()
                .filter(event -> isRun(event, runId) && event.getMessage().contains("topic_counts"))
                .reduce((first, second) -> second).orElseThrow();
        var counts = new HashMap<UUID, Long>();
        var node = (JsonNode) line.getArgumentArray()[2];
        for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext();) {
            var field = fields.next();
            counts.put(UUID.fromString(field.getKey()), field.getValue().asLong());
        }
        return counts;
    }

    /** The run's log lines, with the messages of their exceptions and causes. */
    private List<String> runLog(UUID runId) {
        var lines = new ArrayList<String>();
        logEvents().stream().filter(event -> isRun(event, runId)).forEach(event -> {
            lines.add(event.getFormattedMessage());
            for (var cause = event.getThrowableProxy(); cause != null; cause = cause.getCause()) {
                lines.add(String.valueOf(cause.getMessage()));
            }
        });
        return lines;
    }

    private List<String> nightLog() {
        return logEvents().stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("Topics night")).toList();
    }

    private static boolean isRun(ILoggingEvent event, UUID runId) {
        return event.getMessage().startsWith("Topics run '{}'") && event.getArgumentArray() != null
                && runId.equals(event.getArgumentArray()[0]);
    }

    /** Points the topics' prototypes along an axis no trace is near, so they hold no trace on the next night. */
    private void moveAway(List<Topic> topics) {
        template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class).updateTopics(topics.stream()
                .map(topic -> topic.toBuilder()
                        .vectors(new PrototypeVectors(List.of(TopicsTestData.unit(TopicEmbedding.DIMENSIONS - 1))))
                        .build())
                .toList()));
    }

    /**
     * Records {@code failures} failed nightly attempts in a row on the project, the last one started now, and returns
     * its run id.
     */
    private UUID failedAttempts(UUID projectId, int failures) {
        var runId = UUID.randomUUID();
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> handle.execute("""
                UPDATE topic_projects SET run_id = ?, run_mode = 'nightly_update', run_status = 'failed',
                    run_error_code = 'timeout', run_started_at = ?, run_finished_at = ?, failed_runs = ?,
                    first_failed_at = ?
                WHERE project_id = ?
                """, runId.toString(), now, now.plusSeconds(1), failures, now.minusSeconds(failures),
                projectId.toString()));
        return runId;
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
            dao.checked(workspaceId, now);
            dao.start(List.of(TopicVectorBackfillDAO.Backfill.builder().workspaceId(workspaceId).projectId(other)
                    .backfillStartedAt(now).backfillLastDoneAt(now).build()));
            dao.record(List.of(TopicVectorBackfillDAO.Progress.builder().workspaceId(workspaceId).projectId(other)
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
