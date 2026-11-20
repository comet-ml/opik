package com.comet.opik.domain.topics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.api.error.ConflictException;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The night pass of the planner as a black box, against real MySQL, ClickHouse and Redis, with opik-inference's
 * {@code /v1/generate} stubbed in WireMock. The night starts at today's midnight (UTC); each test sets its budget. A
 * project gets its list from a real first build for yesterday, so tonight's window holds the same traces. A night is a
 * plan ({@link TopicsRunPlanner#plan}); the pod of the test runs what it queued from the stream. A planted theme comes
 * from {@link TopicsNightlyFixture}. A run's stats and its retired topics are read from its log lines, as an operator
 * reads them.
 */
@DisplayName("topics nightly updates on the run queue")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsNightlyIntegrationTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final TopicsNightlyFixture NIGHTLY = TopicsNightlyFixture.load();
    private static final int SMALL = 50;
    private static final int BUILT = 120;
    private static final int PLANTED = 60;
    /** The product's cap of live topics, written out so that a change to the code's cap fails the test. */
    private static final int CAP = 10;
    private static final Duration WAIT = Duration.ofSeconds(90);
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
                        new CustomConfig("topics.runs.poolingInterval", "200ms"),
                        new CustomConfig("topics.runs.longPollingDuration", "200ms"),
                        new CustomConfig("topics.runs.heartbeatInterval", "1s"),
                        new CustomConfig("topics.runs.abandonGrace", "5s"),
                        new CustomConfig("topics.nightly.schedule", "0 0 0 * * ?")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TransactionTemplateAsync clickHouseTemplate;
    private TopicsRunPlanner planner;
    private TopicsTestData data;
    private TopicVectorsDAO vectorsDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private ClientSupport client;
    private String baseUrl;
    /** The nightly run each project got from the test's plans, by project. */
    private final Map<UUID, UUID> nightlyRuns = new HashMap<>();
    /** The run, labelling and night log lines of the test. */
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers = Stream.of(TopicsRebuildService.class, TopicsLlm.class,
            TopicWindowLabelService.class, TopicsNightPass.class, TopicRunStore.class)
            .map(type -> (Logger) LoggerFactory.getLogger(type)).toList();

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicsRunPlanner planner,
            @Jit TopicVectorsDAO vectorsDAO, @Jit TraceTopicsDAO traceTopicsDAO) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.opikConfig = opikConfig;
        this.template = template;
        this.clickHouseTemplate = clickHouseTemplate;
        this.planner = planner;
        this.vectorsDAO = vectorsDAO;
        this.traceTopicsDAO = traceTopicsDAO;
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
        // Each test plans its own workspace: the earlier ones must not take a slot or be decided again.
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE");
            handle.execute("UPDATE topic_projects SET run_status = 'failed' "
                    + "WHERE run_status IN ('queued', 'running', 'cancelling')");
            return null;
        });
        var topics = opikConfig.getTopics();
        topics.setEnabledWorkspaces("");
        topics.getRuns().setMaxInFlight(10);
        topics.getNightly().setBudget(io.dropwizard.util.Duration.days(1));
        stubAnswer(FIXTURE.firstBuildAnswer);
        nightlyRuns.clear();
        synchronized (appender) {
            appender.list.clear();
        }
        appender.start();
        loggers.forEach(logger -> logger.addAppender(appender));
    }

    @AfterEach
    void tearDown() {
        loggers.forEach(logger -> logger.detachAppender(appender));
    }

    @Test
    @DisplayName("two plans of one night queue one nightly update per project with a list, and skip one under "
            + "minVectors; a pod saves each update and then runs its labelling pass; a later plan queues nothing")
    void oneNightlyUpdatePerProjectPerNight() {
        var workspaceId = enabledWorkspace();
        var first = builtYesterday(workspaceId);
        var second = builtYesterday(workspaceId);
        var small = UUID.randomUUID();
        data.seed(workspaceId, small, 0, SMALL, todayStart());
        listedYesterday(workspaceId, small);
        stubAnswer(emptyNaming());

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(2);
        var nightlyRuns = new HashMap<UUID, UUID>();
        for (var projectId : List.of(first, second)) {
            assertThat(data.topicProject(projectId)).satisfies(project -> {
                assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.NIGHTLY_UPDATE);
                assertThat(project.lastNight()).isEqualTo(today());
                nightlyRuns.put(projectId, project.runId());
            });
        }
        assertThat(planner.plan(Instant.now()).block()).isZero();

        for (var projectId : List.of(first, second)) {
            Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId))
                    .satisfies(project -> {
                        assertThat(project.listRunId()).isEqualTo(nightlyRuns.get(projectId));
                        assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
                        assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
                        assertThat(project.labelsComplete()).isTrue();
                    }));
            assertThat(data.topicProject(projectId).windowUntil()).isEqualTo(todayStart());
        }
        assertThat(data.topicProject(small)).satisfies(project -> {
            assertThat(project.lastNight()).isEqualTo(today());
            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.FIRST_BUILD);
        });

        assertThat(planner.plan(Instant.now()).block()).isZero();
        for (var projectId : List.of(first, second)) {
            assertThat(data.topicProject(projectId).savedRuns()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("after the budget no nightly update is queued: each project with a list the night did not look at "
            + "has its not-reached streak counted once, however many plans run; a project the night looked at keeps 0")
    void budgetCutOff() {
        var workspaceId = enabledWorkspace();
        var notReached = UUID.randomUUID();
        var looked = UUID.randomUUID();
        for (var projectId : List.of(notReached, looked)) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), todayStart());
            listedYesterday(workspaceId, projectId);
        }
        template.inTransaction(WRITE, handle -> handle.attach(TopicProjectNightDAO.class)
                .markLooked(workspaceId, List.of(looked), today()));
        opikConfig.getTopics().getNightly().setBudget(io.dropwizard.util.Duration.milliseconds(1));

        assertThat(planner.plan(Instant.now()).block()).isZero();
        assertThat(planner.plan(Instant.now()).block()).isZero();

        assertThat(data.topicProject(notReached)).satisfies(project -> {
            assertThat(project.notReachedNights()).isEqualTo(1);
            assertThat(project.lastNight()).isNull();
            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.FIRST_BUILD);
        });
        assertThat(data.topicProject(looked).notReachedNights()).isZero();
    }

    @Test
    @DisplayName("a night by hand queues the project's nightly update and a pod saves it; it is refused (409) while "
            + "the run is in flight, once the night is saved, and for a project with no list")
    void nightByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = builtYesterday(workspaceId);
        var listless = UUID.randomUUID();
        data.seed(workspaceId, listless, 0, FIXTURE.size(), todayStart());
        stubAnswer(emptyNaming());

        var runId = planner.nightByHand(workspaceId, projectId, today()).block();
        assertThatThrownBy(() -> planner.nightByHand(workspaceId, projectId, today()).block())
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> planner.nightByHand(workspaceId, listless, today()).block())
                .isInstanceOf(ConflictException.class);

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId).listRunId())
                .isEqualTo(runId));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId).runStatus())
                .isNotEqualTo(TopicProject.RunStatus.RUNNING).isNotEqualTo(TopicProject.RunStatus.QUEUED));
        assertThatThrownBy(() -> planner.nightByHand(workspaceId, projectId, today()).block())
                .isInstanceOf(ConflictException.class);
        assertThat(data.topicProject(projectId).lastNight()).isNull();
    }

    @Test
    @DisplayName("an update births the planted theme through the naming call, keeps the list's topics, and labels "
            + "every trace of the window with the live topics or Other")
    void nightlyUpdate() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var before = data.topics(projectId);
        plant(workspaceId, projectId);
        stubNightly(NAMED, sameAnswers());

        var result = runNight(Instant.now());

        assertThat(result.saved()).isEqualTo(1);
        var update = nightlyRun(projectId);
        var stats = update.stats();
        assertThat(stats.path("candidates").asInt()).isPositive();
        assertThat(stats.path("big").asInt()).isPositive();
        assertThat(stats.path("forced").path("born").asInt()).isPositive();
        assertThat(stats.path("born").asInt()).isPositive();
        assertThat(stats.path("churn").isNumber()).isTrue();
        assertThat(stats.path("llm_calls").asInt()).isEqualTo(1);
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
                    assertThat(topic.born()).isEqualTo(today());
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
            assertThat(project.lastNight()).isEqualTo(today());
            assertThat(project.notReachedNights()).isZero();
        });
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
        NightResult result;
        try {
            result = runNight(Instant.now());
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

        var result = runNight(Instant.now());

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

        var result = runNight(Instant.now());

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

        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);

        var stats = nightlyRun(projectId).stats();
        assertThat(stats.path("born").asInt()).isZero();
        assertThat(stats.path("forced").path("born").asInt()).isZero();
        assertThat(stats.path("llm_calls").asInt()).isEqualTo(1);
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
        var debugged = Stream.of(TopicsRebuildService.class, TopicsLlm.class)
                .map(type -> (Logger) LoggerFactory.getLogger(type)).toList();
        var levels = debugged.stream().map(Logger::getLevel).toList();
        var debug = new ListAppender<ILoggingEvent>();
        debug.start();
        debugged.forEach(logger -> {
            logger.addAppender(debug);
            logger.setLevel(Level.DEBUG);
        });
        try {
            assertThat(runNight(Instant.now()).saved()).isEqualTo(1);
        } finally {
            for (int i = 0; i < debugged.size(); i++) {
                debugged.get(i).detachAppender(debug);
                debugged.get(i).setLevel(levels.get(i));
            }
        }

        var texts = List.of(NIGHTLY.intents);
        List<String> messages;
        synchronized (debug) {
            messages = debug.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
        assertThat(messages).filteredOn(message -> message.contains("prompt, call 'naming'")).singleElement()
                .satisfies(message -> assertThat(message).containsPattern("chars '\\d+', sha256 '[0-9a-f]{64}'"));
        assertThat(messages).noneMatch(message -> message.contains(NEW_THEME))
                .noneMatch(message -> message.contains(NAMING_PROMPT))
                .noneMatch(message -> texts.stream().anyMatch(message::contains));
    }

    @Test
    @DisplayName("a nightly update that retires every topic deletes them, clears the list and writes no labels; the "
            + "project has no list, so a first build makes it again with new topic ids")
    void allRetiredIsBuiltAgain() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var firstRun = data.topicProject(projectId).listRunId();
        var before = data.topics(projectId);
        // The topics' prototypes point away from every trace, so they hold no trace tonight and retire; the naming
        // call births nothing.
        moveAway(before);
        stubAnswer(emptyNaming());

        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);

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

        var rebuilt = buildByHand(workspaceId, projectId, today());

        assertThat(data.topicProject(projectId).listRunId()).isEqualTo(rebuilt);
        assertThat(data.topics(projectId)).isNotEmpty()
                .allSatisfy(topic -> {
                    assertThat(topic.born()).isEqualTo(today());
                    assertThat(before).extracting(Topic::id).doesNotContain(topic.id());
                });
    }

    @Test
    @DisplayName("over three nights a topic's low-share streak is saved and read back: it retires on the third, its "
            + "row is deleted and its retirement logged; each night queues one nightly update of the project")
    void threeNights() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        // The fixture's traces are 21 to 29 days before today: the first build's window, three days back, and the
        // three nights' windows hold all of them.
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), todayStart());
        buildByHand(workspaceId, projectId, today().minusDays(3));
        wireMock.resetAll();
        // A tire topic with one trace in a window of more than 200: a share under 0.5% each night.
        var tire = Topic.builder()
                .id(ID_GENERATOR.generateId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name("Tire Questions")
                .definition("Questions about the car's tires.")
                .born(today().minusDays(3))
                .lowNights(0)
                .vectors(new PrototypeVectors(List.of(tireCentre())))
                .build();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertTopics(List.of(tire));
            return null;
        });
        plant(workspaceId, projectId, nearestToTireCentre(), 1, today().minusDays(3));
        stubNightly(emptyNaming(), sameAnswers());

        for (int night = 1; night <= 3; night++) {
            // The night's start: its midnight fire, a minute in.
            var start = today().minusDays(3 - night).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60);
            assertThat(runNight(start).saved()).as("night %d", night).isEqualTo(1);
            var runId = data.topicProject(projectId).listRunId();
            assertThat(planner.plan(start.plusSeconds(60)).block()).as("night %d", night).isZero();
            assertThat(data.topicProject(projectId)).satisfies(project -> {
                assertThat(project.listRunId()).isEqualTo(runId);
                assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
                assertThat(project.lastNight()).isEqualTo(LocalDate.ofInstant(start, ZoneOffset.UTC));
            });
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
            + "a later plan's labels run: the run's rows are written and its labels marked complete")
    void nightAfterLostInsert() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        stubNightly(emptyNaming(), sameAnswers());
        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);
        var lost = nightlyRun(projectId);
        // As after an insert out of retries and a pass cut by its deadline: no row of the run, labels not complete.
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement(
                "ALTER TABLE trace_topics DELETE WHERE run_id = '%s' SETTINGS mutations_sync = 2"
                        .formatted(lost.runId()))
                .execute()).flatMap(result -> Mono.from(result.getRowsUpdated()))).block();
        template.inTransaction(WRITE, handle -> handle.execute(
                "UPDATE topic_projects SET labels_complete = FALSE WHERE project_id = ?", projectId.toString()));
        // The delete is a mutation: wait until it is applied.
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(labels(workspaceId, projectId, lost.runId())).isEmpty());

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId))
                .satisfies(project -> {
                    assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
                    assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
                    assertThat(project.labelsComplete()).isTrue();
                }));
        assertThat(data.topicProject(projectId).listRunId()).isEqualTo(lost.runId());
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
        // A trace created after the night's window ends, with a vector and the arrival row the old list gave.
        var late = vectorAfterTheWindow(workspaceId, projectId);
        var traceId = late.traceId();
        traceTopicsDAO.insert(List.of(TraceTopic.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(late.traceCreatedAt())
                .topicId(retiring.id())
                .source(TraceTopic.Source.ARRIVAL)
                .runId(firstRun)
                .build())).block();
        stubNightly(emptyNaming(), sameAnswers());

        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);

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
    @DisplayName("a project without a list is not the night's: no nightly update, and the night does not mark it")
    void noListSkip() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT, yesterdayStart());

        runNight(Instant.now());

        // The plan queues its first build instead: wait for it and its labels run, so they do not run into the next
        // test.
        Awaitility.await().atMost(WAIT).untilAsserted(() -> {
            var project = data.topicProject(projectId);
            if (project != null && project.runStatus() != null) {
                assertThat(project.runMode() == TopicProject.RunMode.LABELS
                        ? !project.runStatus().inFlight()
                        : project.runStatus() == TopicProject.RunStatus.FAILED).isTrue();
            }
        });
        assertThat(nightlyRuns).doesNotContainKey(projectId);
        var project = data.topicProject(projectId);
        if (project != null) {
            assertThat(project.runMode()).isNotEqualTo(TopicProject.RunMode.NIGHTLY_UPDATE);
            assertThat(project.lastNight()).isNull();
        }
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

        var queued = plan(Instant.now());
        assertThat(queued).containsOnlyKeys(projectId);
        // While the LLM names the groups, another run saves the project.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1));
        var other = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> handle.execute(
                "UPDATE topic_projects SET list_run_id = ? WHERE project_id = ?", other.toString(),
                projectId.toString()));
        awaitEnded(projectId, queued.get(projectId));

        assertThat(nightlyRun(projectId)).satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(run.errorCode()).isEqualTo("save");
            // A save error's message is never logged: it can quote trace text. The failed line names the code.
            assertThat(runLog(run.runId())).anyMatch(line -> line.contains("failed") && line.contains("code 'save'"));
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
    @DisplayName("a run that outlasts projectTimeout fails with timeout and keeps the previous list; the night's other "
            + "project still saves")
    void projectTimeout() {
        var workspaceId = enabledWorkspace();
        var first = firstBuilt(workspaceId);
        var second = firstBuilt(workspaceId);
        plant(workspaceId, first);
        plant(workspaceId, second);
        // The night's first LLM call answers after the project timeout; every later call answers at once.
        stubAnswerInScenario(NAMED, 4_000, Scenario.STARTED, "answered");
        stubAnswerInScenario(NAMED, 0, "answered", "answered");
        var build = opikConfig.getTopics().getBuild();
        var projectTimeout = build.getProjectTimeout();
        build.setProjectTimeout(io.dropwizard.util.Duration.seconds(2));
        NightResult result;
        UUID timedOut;
        try {
            result = runNight(Instant.now());
            timedOut = nightlyRun(first).status() == TopicProject.RunStatus.FAILED ? first : second;
            var topics = data.topics(timedOut);
            // The cut update may still get its slow answer: past it, a save that raced the timeout would show.
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
        } finally {
            build.setProjectTimeout(projectTimeout);
        }

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        var saved = timedOut.equals(first) ? second : first;
        assertThat(nightlyRun(saved).status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topicProject(saved).savedRuns()).isEqualTo(2);
    }

    @Test
    @DisplayName("the night endpoint queues the project's nightly update (202 with its run id) and a pod saves it; it "
            + "answers 409 while the run is in flight, once the night is saved and for a project with no list, and 400 "
            + "for a night that is not a date")
    void nightEndpoint() {
        var workspaceId = enabledWorkspace();
        var projectId = firstBuilt(workspaceId);
        var listless = UUID.randomUUID();
        data.seed(workspaceId, listless, 0, BUILT, todayStart());
        stubAnswer(emptyNaming());

        UUID runId;
        try (var response = nightEndpoint(workspaceId, projectId, today().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
            runId = UUID.fromString(response.readEntity(JsonNode.class).path("run_id").asText());
        }
        try (var response = nightEndpoint(workspaceId, projectId, today().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = nightEndpoint(workspaceId, listless, today().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = nightEndpoint(workspaceId, projectId, "tomorrow")) {
            assertThat(response.getStatus()).isEqualTo(400);
        }

        awaitEnded(projectId, runId);
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.listRunId()).isEqualTo(runId);
            assertThat(project.windowUntil()).isEqualTo(todayStart());
            // One project's run, not a night: the night fields stay.
            assertThat(project.lastNight()).isNull();
        });
        try (var response = nightEndpoint(workspaceId, projectId, today().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("at or after the night");
        }
    }

    @Test
    @DisplayName("a project with a list in a workspace opted in to ai-labels but not to topics gets no nightly update")
    void topicsOptIn() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT, yesterdayStart());
        data.savedList(workspaceId, projectId, yesterdayStart(), BUILT);

        runNight(Instant.now());

        assertThat(nightlyRuns).doesNotContainKey(projectId);
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
        stubAnswer(emptyNaming());

        var result = runNight(Instant.now());

        assertThat(result.saved()).isEqualTo(1);
        assertThat(nightlyRuns).containsOnlyKeys(twoFailures);
        assertThat(data.topicProject(paused)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(pausedRun);
            assertThat(project.failedRuns()).isEqualTo(3);
            assertThat(project.lastNight()).isEqualTo(today());
        });
        assertThat(nightLog()).anyMatch(line -> line.contains(paused.toString()) && line.contains("reason 'paused'"));
        assertThat(nightlyRun(twoFailures).status()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topicProject(twoFailures).failedRuns()).isZero();
    }

    /** A project with a list from a real first build for yesterday, its labels complete. */
    private UUID builtYesterday(String workspaceId) {
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), yesterdayStart());
        buildByHand(workspaceId, projectId, today().minusDays(1));
        return projectId;
    }

    /** A project of {@value #BUILT} traces with a list from a first build by hand for yesterday, its labels complete. */
    private UUID firstBuilt(String workspaceId) {
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT, yesterdayStart());
        buildByHand(workspaceId, projectId, today().minusDays(1));
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
        wireMock.resetAll();
        return projectId;
    }

    /** Builds the project's first list by hand for {@code night}; returns its run once it and its labels run ended. */
    private UUID buildByHand(String workspaceId, UUID projectId, LocalDate night) {
        stubAnswer(FIXTURE.firstBuildAnswer);
        var runId = planner.runByHand(workspaceId, projectId, night, true).block();
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId))
                .satisfies(project -> {
                    assertThat(project.listRunId()).isEqualTo(runId);
                    assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
                    assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
                }));
        return runId;
    }

    /** One plan at {@code start}, then each nightly update it queued and the labels run that follows a save. */
    private NightResult runNight(Instant start) {
        var queued = plan(start);
        queued.forEach(this::awaitEnded);
        return new NightResult(queued.keySet().stream().map(this::nightlyRun).toList());
    }

    /** One plan at {@code start}; the nightly updates it queued, by project, from its log lines. */
    private Map<UUID, UUID> plan(Instant start) {
        int from = logEvents().size();
        planner.plan(start).block();
        var queued = logEvents().subList(from, logEvents().size()).stream()
                .filter(event -> event.getMessage().startsWith("Topics night '{}' queued run '{}'"))
                .collect(Collectors.toMap(event -> (UUID) event.getArgumentArray()[3],
                        event -> (UUID) event.getArgumentArray()[1]));
        nightlyRuns.putAll(queued);
        return queued;
    }

    /** Until the run failed, or it saved and the labels run it queued ended. */
    private void awaitEnded(UUID projectId, UUID runId) {
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId))
                .satisfies(project -> {
                    if (runId.equals(project.runId())) {
                        assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
                    } else {
                        assertThat(project.runStatus().inFlight()).isFalse();
                    }
                }));
    }

    /** The night's nightly updates, once ended. */
    private record NightResult(List<NightlyRun> runs) {

        long saved() {
            return runs.stream().filter(run -> run.status() == TopicProject.RunStatus.SAVED).count();
        }

        long failed() {
            return runs.stream().filter(run -> run.status() == TopicProject.RunStatus.FAILED).count();
        }
    }

    /** A project's nightly update, with the stats its log line holds. */
    private record NightlyRun(UUID runId, TopicProject.RunStatus status, String errorCode, JsonNode stats) {
    }

    /**
     * The project's last nightly update from the test's plans. Its row holds it until a saved update's labels run
     * takes the row, so a row on another run means the update saved.
     */
    private NightlyRun nightlyRun(UUID projectId) {
        var runId = nightlyRuns.get(projectId);
        assertThat(runId).as("nightly update of project '%s'", projectId).isNotNull();
        var project = data.topicProject(projectId);
        if (!runId.equals(project.runId())) {
            return new NightlyRun(runId, TopicProject.RunStatus.SAVED, null, stats(runId));
        }
        assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.NIGHTLY_UPDATE);
        return new NightlyRun(runId, project.runStatus(),
                project.runErrorCode() == null ? null : project.runErrorCode().getValue(), stats(runId));
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

    /** The trace_topics rows the run wrote. */
    private List<TraceTopic> labels(String workspaceId, UUID projectId, UUID runId) {
        return data.traceTopics(workspaceId, projectId).stream()
                .filter(row -> runId.equals(row.runId())).toList();
    }

    /** The {@value #PLANTED} traces of the nightly fixture's theme, created yesterday. */
    private List<TopicVector> plant(String workspaceId, UUID projectId) {
        return plant(workspaceId, projectId, 0, PLANTED, today().minusDays(1));
    }

    /** The nightly fixture's traces {@code [from, from + count)}, created on {@code day}, a minute apart. */
    private List<TopicVector> plant(String workspaceId, UUID projectId, int from, int count, LocalDate day) {
        int d = TopicsNightlyFixture.DIMENSIONS;
        var dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        var rows = new ArrayList<TopicVector>(count);
        for (int i = from; i < from + count; i++) {
            rows.add(TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .task(TopicEmbedding.SUMMARY_TASK)
                    .traceId(ID_GENERATOR.generateId())
                    .traceCreatedAt(dayStart.plus(Duration.ofMinutes(i)))
                    .taskVersion(1)
                    .embedModel(TopicEmbedding.MODEL)
                    .embedDimensions(TopicEmbedding.DIMENSIONS)
                    .text(NIGHTLY.intents[i])
                    .vector(Arrays.copyOfRange(NIGHTLY.vectors, i * d, (i + 1) * d))
                    .build());
        }
        vectorsDAO.insert(rows).block();
        return rows;
    }

    /** The vector of a trace created after tonight's window ends (today's start) and before now, the save. */
    private TopicVector vectorAfterTheWindow(String workspaceId, UUID projectId) {
        var start = todayStart();
        var row = TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(TopicEmbedding.SUMMARY_TASK)
                .traceId(ID_GENERATOR.generateId())
                .traceCreatedAt(start.plus(Duration.between(start, Instant.now()).dividedBy(2)))
                .taskVersion(1)
                .embedModel(TopicEmbedding.MODEL)
                .embedDimensions(TopicEmbedding.DIMENSIONS)
                .text(NIGHTLY.intents[0])
                .vector(Arrays.copyOf(NIGHTLY.vectors, TopicsNightlyFixture.DIMENSIONS))
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

    private Response nightEndpoint(String workspaceId, UUID projectId, String night) {
        return client.target("%s/v1/internal/topics/projects/%s/night".formatted(baseUrl, projectId))
                .queryParam("workspace_id", workspaceId)
                .queryParam("night", night)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    /** Stubs the nightly update's calls by prompt: the first naming call, and the verify (a 500 when null). */
    private void stubNightly(JsonNode naming, JsonNode verify) {
        wireMock.resetAll();
        stubAnswer(naming, NAMING_PROMPT, 0);
        if (verify == null) {
            wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).withRequestBody(containing(VERIFY_PROMPT))
                    .willReturn(aResponse().withStatus(500)));
        } else {
            stubAnswer(verify, VERIFY_PROMPT, 0);
        }
    }

    /** Answers {@code output} after {@code delayMillis} to the calls whose body contains {@code prompt}. */
    private void stubAnswer(JsonNode output, String prompt, int delayMillis) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).withRequestBody(containing(prompt))
                .willReturn(TopicsTestData.answer(output, delayMillis)));
    }

    /** {@code output} after {@code delayMillis}, matched only in the scenario's {@code state}, which it then moves on. */
    private void stubAnswerInScenario(JsonNode output, int delayMillis, String state, String nextState) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).inScenario("answers").whenScenarioStateIs(state)
                .willSetStateTo(nextState).willReturn(TopicsTestData.answer(output, delayMillis)));
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

    /** Opted in to ai-labels only, not listed in topics.enabledWorkspaces. */
    private String aiLabelsOnlyWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        return workspaceId;
    }

    /** A saved list with its window until yesterday's start, its labels complete: no run of its own is due. */
    private void listedYesterday(String workspaceId, UUID projectId) {
        data.savedList(workspaceId, projectId, yesterdayStart(), FIXTURE.size());
        template.inTransaction(WRITE, handle -> handle.execute(
                "UPDATE topic_projects SET labels_complete = TRUE WHERE project_id = ?", projectId.toString()));
    }

    private void stubAnswer(JsonNode answer) {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(answer, 0)));
    }

    /** A naming answer that changes nothing. */
    private static JsonNode emptyNaming() {
        var naming = InferenceJson.MAPPER.createObjectNode();
        naming.putArray("assign");
        naming.putArray("new");
        naming.putArray("other");
        return naming;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static Instant todayStart() {
        return today().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant yesterdayStart() {
        return today().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Opted in to both, and seen by the vectors tick with no backlog: one drained backfill row, of another project,
     * so a first build by hand does not wait.
     */
    private String enabledWorkspace() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var topics = opikConfig.getTopics();
        var listed = new TreeSet<>(topics.enabledWorkspaceIds());
        listed.add(workspaceId);
        topics.setEnabledWorkspaces(String.join(",", listed));
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).checked(workspaceId, now);
            var dao = handle.attach(TopicProjectDAO.class);
            var projectId = UUID.randomUUID();
            dao.startBackfills(List.of(TopicProjectDAO.Backfill.builder().workspaceId(workspaceId)
                    .projectId(projectId).backfillStartedAt(now).backfillLastDoneAt(now).build()));
            dao.recordBackfills(List.of(TopicProjectDAO.Progress.builder().workspaceId(workspaceId)
                    .projectId(projectId).drainedAt(now).build()));
            return null;
        });
        return workspaceId;
    }
}
