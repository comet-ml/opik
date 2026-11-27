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
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.dropwizard.util.Duration;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * The night pass of the planner and the nightly update as a black box, against real MySQL, ClickHouse and Redis, with
 * opik-inference's {@code /v1/generate} stubbed in WireMock. The night starts at today's midnight (UTC); each test sets
 * its budget. A project gets its list from a real discovery run for yesterday, so tonight's window holds the same
 * traces. A night is a plan ({@link TopicsRunPlanner#plan}); a pod of the app runs what it queued from the stream. A
 * planted theme comes from {@link TopicsNightlyFixture}. A run's stats and its retired topics are read from its log
 * lines, as an operator reads them.
 */
@DisplayName("topics nightly updates on the run queue")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsNightlyIntegrationTest {

    private static final java.time.Duration PLAN_BUDGET = java.time.Duration.ofMinutes(2);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final TopicsNightlyFixture NIGHTLY = TopicsNightlyFixture.load();
    private static final int SMALL = 50;
    private static final int BUILT = 120;
    private static final int PLANTED = 60;
    /** The product's cap of live topics, written out so that a change to the code's cap fails the test. */
    private static final int CAP = 10;
    private static final long WAIT_SECONDS = 90;
    private static final JsonNode NAMED = named();
    /** Text only the first naming prompt has ({@code topics/tasks/naming.yaml}). */
    private static final String NAMING_PROMPT = "Decide what topic each one gets.";
    /** Text only the verify prompt has ({@code topics/tasks/verify.yaml}). */
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

    /** A run's own ids and times, and its prototypes: new on every run. */
    private static final String[] RUN_FIELDS = {"runId", "listRunId", "otherVectors", "labelsFingerprint",
            "lastSuccessAt",
            "runStartedAt", "runFinishedAt", "runQueuedAt", "deadlineAt", "heartbeatAt"};
    /** The times a failed attempt sets; its run id and code are known, so they are compared. */
    private static final String[] FAILED_FIELDS = {"runStartedAt", "runFinishedAt", "runQueuedAt", "deadlineAt",
            "heartbeatAt", "firstFailedAt", "lastFailedAt"};

    /** The time and run fields a saved nightly update sets, when its row keeps that run. */
    private static final String[] SAVED_FIELDS = {"lastSuccessAt", "runStartedAt", "runFinishedAt", "runQueuedAt",
            "deadlineAt", "heartbeatAt"};

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TransactionTemplateAsync clickHouseTemplate;
    private TopicsRunPlanner planner;
    private TopicsTestData data;
    private TopicRunStore store;
    private TopicWorkspaceService workspaceService;
    private TopicsDiscoveryCandidates candidates;
    private WorkspaceNameService workspaceNames;
    private ProjectService projectService;
    private LockService lockService;
    private TopicVectorsDAO vectorsDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private ClientSupport client;
    private String baseUrl;
    /** The nightly update each project got from the test's plans, by project. */
    private final Map<UUID, UUID> nightlyRuns = new HashMap<>();
    /** The run, labelling and night log lines of the test. */
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers = Stream.of(TopicsRunService.class, TopicRunOutcomes.class, TopicsLlm.class,
            TopicWindowLabelService.class, TopicsNightPass.class, TopicRunStore.class)
            .map(type -> (Logger) LoggerFactory.getLogger(type)).toList();
    private TopicWindowDAO windowDAO;
    private TopicAlgorithm algorithm;

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicsRunPlanner planner,
            @Jit TopicVectorsDAO vectorsDAO, @Jit TopicRunStore store, @Jit TopicWorkspaceService workspaceService,
            @Jit TopicsDiscoveryCandidates candidates, @Jit WorkspaceNameService workspaceNames,
            @Jit ProjectService projectService, @Jit LockService lockService, @Jit TraceTopicsDAO traceTopicsDAO,
            @Jit TopicWindowDAO windowDAO, @Jit TopicAlgorithm algorithm) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.windowDAO = windowDAO;
        this.algorithm = algorithm;
        this.store = store;
        this.workspaceService = workspaceService;
        this.candidates = candidates;
        this.workspaceNames = workspaceNames;
        this.projectService = projectService;
        this.lockService = lockService;
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
        // Each test plans its own workspace: the earlier ones must not take a slot or be decided again. Topics takes a
        // workspace only while it is listed, so clearing the list drops theirs; their runs still in flight would take
        // slots of topics.runs.maxInFlight, which is cluster-wide.
        template.inTransaction(WRITE, handle -> handle.execute("UPDATE topic_projects SET run_status = 'failed' "
                + "WHERE run_status IN ('queued', 'running', 'cancelling')"));
        var topics = opikConfig.getTopics();
        topics.setEnabledWorkspaces("");
        topics.getRuns().setMaxInFlight(10);
        topics.getNightly().setBudget(Duration.days(1));
        stubAnswer(FIXTURE.discoveryAnswer);
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
            + "minVectors; a pod saves each update and then runs its labelling pass, whose labels topic on arrival gives "
            + "too; a plan after that queues nothing")
    void oneNightlyUpdatePerProjectPerNight() {
        var workspaceId = enabledWorkspace();
        var first = discoveredYesterday(workspaceId);
        var second = discoveredYesterday(workspaceId);
        var small = ID_GENERATOR.generateId();
        data.seed(workspaceId, small, 0, SMALL, todayStart());
        listedYesterday(workspaceId, small);
        stubAnswer(emptyNaming());

        var before = Map.of(first, data.topicProject(first), second, data.topicProject(second));
        var smallBefore = data.topicProject(small);

        // At least the two updates: a pod can save one before this plan's labels pass reads, which then queues its
        // labelling pass too. The rows below show what the night decided.
        assertThat(planner.plan(Instant.now()).block()).isGreaterThanOrEqualTo(2);

        for (var projectId : List.of(first, second)) {
            var expected = before.get(projectId).toBuilder().savedRuns(2).windowUntil(todayStart())
                    .labelsComplete(true).runMode(TopicProject.RunMode.LABELS)
                    .runStatus(TopicProject.RunStatus.SAVED).lastNight(today()).build();
            Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(data.topicProject(projectId)).usingRecursiveComparison()
                            .ignoringFields(RUN_FIELDS).isEqualTo(expected));
            var listRunId = data.topicProject(projectId).listRunId();
            assertThat(listRunId).isNotEqualTo(before.get(projectId).listRunId());
            data.assertArrivalEqualsTheLabels(windowDAO, algorithm, workspaceId, projectId, todayStart());
        }
        assertThat(data.topicProject(small)).isEqualTo(smallBefore.toBuilder().lastNight(today()).build());

        assertThat(planner.plan(Instant.now()).block()).isZero();
    }

    @Test
    @DisplayName("within the budget each project with a list is decided once, by reason: a deleted project, a list "
            + "that covers the night, a project paused by its failures and a small window are skipped; the rest is "
            + "queued with its count; a project looked at tonight or with a run in flight is left alone")
    void decidesEachProject() {
        var workspaceId = enabledWorkspace();
        var gone = ID_GENERATOR.generateId();
        data.seedVectors(workspaceId, gone, 0, FIXTURE.size(), todayStart());
        data.savedList(workspaceId, gone, yesterdayStart(), FIXTURE.size());
        var saved = ID_GENERATOR.generateId();
        data.seed(workspaceId, saved, 0, FIXTURE.size(), todayStart());
        data.savedList(workspaceId, saved, todayStart(), FIXTURE.size());
        var paused = seededList(workspaceId, FIXTURE.size());
        failRuns(workspaceId, paused, opikConfig.getTopics().getNightly().getPauseFailures());
        var small = seededList(workspaceId, SMALL);
        var built = seededList(workspaceId, FIXTURE.size());
        var lookedAt = seededList(workspaceId, FIXTURE.size());
        template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class)
                .markLooked(workspaceId, List.of(lookedAt), today()));
        var running = seededList(workspaceId, FIXTURE.size());
        template.inTransaction(WRITE, handle -> {
            TopicsTestData.startRun(handle.attach(TopicProjectDAO.class), workspaceId, running,
                    ID_GENERATOR.generateId(), TopicProject.RunMode.UPDATE, Instant.now());
            return null;
        });
        var untouched = Map.of(lookedAt, data.topicProject(lookedAt), running, data.topicProject(running));
        var reader = InMemoryMetricReader.create();
        var queued = new ArrayList<PlannedRun>();

        assertThat(pass(reader).plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 10, capture(queued)).block())
                .isEqualTo(1);

        assertThat(queued).containsExactly(new PlannedRun(workspaceId, built,
                TopicProject.RunMode.UPDATE, today(), FIXTURE.size()));
        for (var reason : List.of("deleted", "already_saved", "paused", "small")) {
            assertThat(TopicsMetricsReader.sum(reader, "topics.nightly.decisions", "decision", "skip", "reason",
                    reason)).as(reason).isEqualTo(1);
        }
        assertThat(TopicsMetricsReader.sum(reader, "topics.nightly.decisions", "decision", "update")).isEqualTo(1);
        for (var projectId : List.of(gone, saved, paused, small, built)) {
            assertThat(data.topicProject(projectId).lastNight()).as(projectId.toString()).isEqualTo(today());
        }
        untouched.forEach((projectId, before) -> assertThat(data.topicProject(projectId)).isEqualTo(before));
    }

    @Test
    @DisplayName("nightly updates by hand that fail pauseFailures times in a row pause the project: the next night "
            + "skips it as paused and queues nothing for it, and a night by hand still runs it")
    void failedNightsByHandPause() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        plant(workspaceId, projectId);
        // A failed call no longer fails the night; an answer that breaks the schema still does.
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(schemaViolation()));
        int failures = opikConfig.getTopics().getNightly().getPauseFailures();
        // A failed update saves nothing, so the same night can be asked for again.
        for (int i = 0; i < failures; i++) {
            try (var response = nightEndpoint(workspaceId, projectId, today().toString())) {
                assertThat(response.getStatus()).isEqualTo(202);
                awaitEnded(projectId, UUID.fromString(response.readEntity(JsonNode.class).path("run_id").asText()));
            }
        }
        var failed = data.topicProject(projectId);
        assertThat(failed.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
        assertThat(failed.runErrorCode()).isEqualTo(TopicProject.RunError.LLM_BAD_ANSWER);
        assertThat(failed.failedRuns()).isEqualTo(failures);
        var queued = new ArrayList<PlannedRun>();
        int from = logEvents().size();

        // Tomorrow's night, within its budget: a failed update queued tonight waits for it.
        var tomorrowNight = todayStart().plusSeconds(86_400 + 2 * 3_600 + 60);
        pass(InMemoryMetricReader.create()).plan(tomorrowNight, PlanDeadline.after(PLAN_BUDGET), 10, capture(queued))
                .block();

        assertThat(queued).noneMatch(planned -> planned.projectId().equals(projectId));
        var events = logEvents();
        assertThat(events.subList(from, events.size())).anySatisfy(event -> {
            assertThat(event.getMessage()).startsWith("Topics night '{}' decided");
            assertThat(event.getArgumentArray()[1]).isEqualTo(workspaceId);
            assertThat(event.getArgumentArray()[2]).isEqualTo(Set.of(projectId));
            assertThat(event.getArgumentArray()[4]).isEqualTo("paused");
        });
        assertThat(data.topicProject(projectId)).isEqualTo(failed.toBuilder().lastNight(today().plusDays(1)).build());
        // The pause is the night's own: an operator still runs the paused project by hand.
        try (var response = nightEndpoint(workspaceId, projectId, today().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
            var runId = UUID.fromString(response.readEntity(JsonNode.class).path("run_id").asText());
            awaitEnded(projectId, runId);
            assertThat(data.topicProject(projectId).runId()).isEqualTo(runId);
        }
    }

    @Test
    @DisplayName("with no free slot the skips that need no count are still written and nothing is queued; with slots "
            + "it queues up to them, stalest window first; a project that got a run before its queue is not decided")
    void queuesUpToTheFreeSlots() {
        var workspaceId = enabledWorkspace();
        var gone = ID_GENERATOR.generateId();
        data.seedVectors(workspaceId, gone, 0, FIXTURE.size(), todayStart());
        data.savedList(workspaceId, gone, yesterdayStart(), FIXTURE.size());
        var older = ID_GENERATOR.generateId();
        data.seed(workspaceId, older, 0, FIXTURE.size(), todayStart());
        data.savedList(workspaceId, older, yesterdayStart().minusSeconds(2 * 86_400), FIXTURE.size());
        var newer = seededList(workspaceId, FIXTURE.size());
        var reader = InMemoryMetricReader.create();
        var queued = new ArrayList<PlannedRun>();

        assertThat(pass(reader).plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 0, capture(queued)).block())
                .isZero();
        assertThat(data.topicProject(gone).lastNight()).isEqualTo(today());
        assertThat(queued).isEmpty();

        assertThat(
                pass(reader).plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 1, planned -> Mono.empty()).block())
                .isZero();
        assertThat(data.topicProject(older).lastNight()).isNull();

        assertThat(pass(reader).plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 1, capture(queued)).block())
                .isEqualTo(1);
        assertThat(queued).extracting(PlannedRun::projectId).containsExactly(older);
        assertThat(data.topicProject(newer).lastNight()).isNull();
    }

    @Test
    @DisplayName("after the budget no nightly update is queued: each project with a list the night did not look at "
            + "has its not-reached streak counted once, however many plans run; a project the night looked at keeps 0; "
            + "the night is counted finished once")
    void budgetCutOff() {
        var workspaceId = enabledWorkspace();
        var notReached = ID_GENERATOR.generateId();
        var looked = ID_GENERATOR.generateId();
        for (var projectId : List.of(notReached, looked)) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), todayStart());
            listedYesterday(workspaceId, projectId);
        }
        template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class)
                .markLooked(workspaceId, List.of(looked), today()));
        var notReachedBefore = data.topicProject(notReached);
        var lookedBefore = data.topicProject(looked);
        opikConfig.getTopics().getNightly().setBudget(Duration.milliseconds(1));
        // Redis is shared with other test classes, whose own plans after the budget may have ended this night already.
        var night = TopicsNight.current(opikConfig.getTopics().getNightly().getSchedule(), Instant.now()).orElseThrow();
        lockService.unlockUsingToken(new LockService.Lock("topics:nightly:end:" + night.date())).block();
        var reader = InMemoryMetricReader.create();
        var pass = pass(reader);

        assertThat(pass.plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 10, planned -> Mono.empty()).block())
                .isZero();
        assertThat(pass.plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 10, planned -> Mono.empty()).block())
                .isZero();

        assertThat(data.topicProject(notReached))
                .isEqualTo(notReachedBefore.toBuilder().notReachedNights(1).build());
        assertThat(data.topicProject(looked)).isEqualTo(lookedBefore);
        assertThat(TopicsMetricsReader.sum(reader, "topics.nightly.nights", "outcome", "finished")).isEqualTo(1);
    }

    @Test
    @DisplayName("the night queues one workspace at a time in turn, stalest first within each: a workspace with three "
            + "stale projects does not take both free slots from one with a fresher project")
    void nightSharesTheSlotsAcrossWorkspaces() {
        var crowded = enabledWorkspace();
        var stalest = seededList(crowded, FIXTURE.size(), 5);
        seededList(crowded, FIXTURE.size(), 4);
        seededList(crowded, FIXTURE.size(), 3);
        var other = enabledWorkspace();
        var fresher = seededList(other, FIXTURE.size(), 1);
        var queued = new ArrayList<PlannedRun>();

        assertThat(pass(InMemoryMetricReader.create())
                .plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 2, capture(queued)).block()).isEqualTo(2);

        assertThat(queued).extracting(PlannedRun::projectId).containsExactly(stalest, fresher);
    }

    @Test
    @DisplayName("the night counts a workspace only when its turn comes: with five workspaces of two projects and two "
            + "free slots it counts two workspaces, queues one project in each, and records nothing on a project it "
            + "counted but did not queue")
    void nightCountsLazily() {
        var workspaces = IntStream.range(0, 5).mapToObj(i -> enabledWorkspace()).toList();
        var projects = workspaces.stream()
                .flatMap(workspaceId -> Stream.of(seededList(workspaceId, FIXTURE.size()),
                        seededList(workspaceId, FIXTURE.size())))
                .toList();
        var queued = new ArrayList<PlannedRun>();

        assertThat(pass(InMemoryMetricReader.create())
                .plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 2, capture(queued)).block()).isEqualTo(2);

        assertThat(queued).extracting(PlannedRun::workspaceId).doesNotHaveDuplicates();
        assertThat(data.finishedQueries("topics:count_window", workspaces)).isEqualTo(2);
        var queuedIds = queued.stream().map(PlannedRun::projectId).toList();
        for (var projectId : projects) {
            assertThat(data.topicProject(projectId).lastNight()).as(projectId.toString())
                    .isEqualTo(queuedIds.contains(projectId) ? today() : null);
        }
    }

    @Test
    @DisplayName("the night records a workspace's small projects skipped as small and its projects whose count fails "
            + "as count_failed, and queues none of them")
    void nightRecordsSmallAndCountFailed() {
        var workspaceId = enabledWorkspace();
        var small = List.of(seededList(workspaceId, SMALL), seededList(workspaceId, SMALL),
                seededList(workspaceId, SMALL));
        var failed = Set.of(seededList(workspaceId, FIXTURE.size()), seededList(workspaceId, FIXTURE.size()));
        // A real count cannot be made to fail on demand: these projects' counts fail as a guard trip makes them.
        var failing = spy(windowDAO);
        doAnswer(invocation -> invocation.<Collection<UUID>>getArgument(1).stream().anyMatch(failed::contains)
                ? Mono.error(new IllegalStateException("count failed"))
                : invocation.callRealMethod())
                .when(failing).countByProject(anyString(), anyCollection(), any(), any(), anyLong());
        var config = opikConfig.getTopics();
        var reader = InMemoryMetricReader.create();
        var pass = pass(new TopicsDiscoveryCandidates(config, template, failing, new TopicsBackfillGate(config)),
                reader);
        var queued = new ArrayList<PlannedRun>();

        assertThat(pass.plan(Instant.now(), PlanDeadline.after(PLAN_BUDGET), 10, capture(queued)).block()).isZero();

        assertThat(queued).isEmpty();
        assertThat(TopicsMetricsReader.sum(reader, "topics.nightly.decisions", "decision", "skip", "reason", "small"))
                .isEqualTo(3);
        assertThat(TopicsMetricsReader.sum(reader, "topics.nightly.decisions", "decision", "count_failed"))
                .isEqualTo(2);
        for (var projectId : Stream.concat(small.stream(), failed.stream()).toList()) {
            assertThat(data.topicProject(projectId).lastNight()).as(projectId.toString()).isEqualTo(today());
        }
    }

    @Test
    @DisplayName("one deadline covers the night pass and the discovery phase: with counts that take 30 s and a 1 s "
            + "plan budget, the plan cuts them, ends within 1.8 s and queues nothing")
    void oneDeadlinePerPlan(@Jit TopicsRunReconciler reconciler, @Jit TopicsRunPublisher publisher) {
        var workspaceId = enabledWorkspace();
        var listed = seededList(workspaceId, FIXTURE.size());
        var listless = ID_GENERATOR.generateId();
        data.seed(workspaceId, listless, 0, FIXTURE.size(), todayStart());
        var listedBefore = data.topicProject(listed);
        // A real count cannot be made slow on demand.
        var slow = spy(windowDAO);
        doAnswer(invocation -> Mono.delay(java.time.Duration.ofSeconds(30)).thenReturn(Map.<UUID, Long>of()))
                .when(slow).countByProject(anyString(), anyCollection(), any(), any(), anyLong());
        var config = opikConfig.getTopics();
        var slowCandidates = new TopicsDiscoveryCandidates(config, template, slow, new TopicsBackfillGate(config));
        var slowPass = pass(slowCandidates, InMemoryMetricReader.create());
        var slowPlanner = new TopicsRunPlanner(config, template, workspaceService, store, reconciler, slowCandidates,
                publisher, new TopicsBackfillGate(config), slowPass, lockService);
        var budget = config.getRuns().getPlanBudget();
        config.getRuns().setPlanBudget(Duration.seconds(1));
        try {
            long started = System.nanoTime();

            assertThat(slowPlanner.plan(Instant.now()).block()).isZero();

            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(java.time.Duration.ofMillis(1_800));
        } finally {
            config.getRuns().setPlanBudget(budget);
        }
        assertThat(data.topicProject(listed)).isEqualTo(listedBefore);
        var listlessRow = data.topicProject(listless);
        assertThat(listlessRow == null ? null : listlessRow.runId()).isNull();
    }

    @Test
    @DisplayName("a night by hand queues the project's nightly update and a pod saves it, with no night field "
            + "written; it is refused (409) while the run is in flight, and with the night's skip reason for a project "
            + "with no list, a deleted project, a window under minVectors and once the night is saved")
    void nightByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = discoveredYesterday(workspaceId);
        var listless = ID_GENERATOR.generateId();
        data.seed(workspaceId, listless, 0, FIXTURE.size(), todayStart());
        var gone = ID_GENERATOR.generateId();
        data.seedVectors(workspaceId, gone, 0, FIXTURE.size(), todayStart());
        data.savedList(workspaceId, gone, yesterdayStart(), FIXTURE.size());
        var small = seededList(workspaceId, SMALL);
        stubAnswer(emptyNaming());

        var runId = planner.nightByHand(workspaceId, projectId, today()).block();
        assertRefusedByHand(workspaceId, projectId, "Project has a topics run in flight, project '" + projectId + "'");
        assertRefusedByHand(workspaceId, listless, skippedByHand(listless, "no_list"));
        assertRefusedByHand(workspaceId, gone, skippedByHand(gone, "deleted"));
        assertRefusedByHand(workspaceId, small, skippedByHand(small, "small") + ", vectors '" + SMALL
                + "', minimum '" + opikConfig.getTopics().getRun().getMinVectors() + "'");

        Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(data.topicProject(projectId)).satisfies(row -> {
                    assertThat(row.listRunId()).isEqualTo(runId);
                    assertThat(row.runStatus()).isNotIn(TopicProject.RunStatus.QUEUED,
                            TopicProject.RunStatus.RUNNING);
                }));
        assertRefusedByHand(workspaceId, projectId, skippedByHand(projectId, "already_saved"));
        assertThat(data.topicProject(projectId).lastNight()).isNull();
    }

    /** The night by hand of the project today is refused with exactly {@code message}. */
    private void assertRefusedByHand(String workspaceId, UUID projectId, String message) {
        assertThatThrownBy(() -> planner.nightByHand(workspaceId, projectId, today()).block())
                .isInstanceOfSatisfying(ConflictException.class, error -> assertThat(
                        ((ErrorMessage) error.getResponse().getEntity()).getMessage()).isEqualTo(message));
    }

    /** The refusal of tonight's night by hand for a project the night would skip with {@code reason}. */
    private static String skippedByHand(UUID projectId, String reason) {
        return "Project is skipped for the night, project '%s', night '%s', reason '%s'".formatted(projectId, today(),
                reason);
    }

    @Test
    @DisplayName("a workspace listed at stage vectors gets no nightly update, and is refused one by hand (409); one "
            + "listed at nightly gets its update")
    void stageBeforeNightlyIsSkipped() {
        var atNightly = enabledWorkspace();
        var projectId = discoveredYesterday(atNightly);
        var vectorsOnly = ID_GENERATOR.generateId().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(vectorsOnly, true, "test");
            return null;
        });
        var skipped = ID_GENERATOR.generateId();
        data.seed(vectorsOnly, skipped, 0, FIXTURE.size(), todayStart());
        listedYesterday(vectorsOnly, skipped);
        opikConfig.getTopics().setEnabledWorkspaces(atNightly + ":nightly," + vectorsOnly + ":vectors");
        var before = data.topicProject(skipped);
        stubAnswer(emptyNaming());

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);

        assertThat(data.topicProject(projectId).runMode()).isEqualTo(TopicProject.RunMode.UPDATE);
        assertThat(data.topicProject(skipped)).isEqualTo(before);
        assertThatThrownBy(() -> planner.nightByHand(vectorsOnly, skipped, today()).block())
                .isInstanceOfSatisfying(ConflictException.class, error -> assertThat(
                        ((ErrorMessage) error.getResponse().getEntity()).getMessage())
                        .startsWith("Workspace is not opted in to topics runs:").endsWith("stage 'nightly'"));
    }

    @Test
    @DisplayName("an update births the planted theme through the naming call, keeps the list's topics, and labels "
            + "every trace of the window with the live topics or Other")
    void nightlyUpdate() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var before = data.topics(projectId);
        var row = data.topicProject(projectId);
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
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).singleElement()
                .satisfies(request -> assertThat(request.getBodyAsString()).contains(NAMING_PROMPT)
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

        assertSavedNight(projectId, row, update.runId(), todayStart(), today());
        // Every window trace's topic, whichever run wrote it: the pass writes only the ones that changed.
        var labels = windowLabels(workspaceId, projectId);
        assertThat(labels).hasSize(stats.path("vectors").asInt())
                .allSatisfy(label -> assertThat(label.topicId().equals(TraceTopic.OTHER)
                        || liveIds.contains(label.topicId())).isTrue());
        live.forEach(topic -> assertThat(labels.stream().filter(label -> label.topicId().equals(topic.id())))
                .hasSize(topic.size()));
    }

    @Test
    @DisplayName("a night whose naming assigns the planted groups to a live topic makes its three calls in turn: the "
            + "naming, the verify, which rejects every pair, and the second naming, which births the theme; each "
            + "call has its tag in the request id and its kind's deadline")
    void threeCallsInTurn() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        plant(workspaceId, projectId);
        stubCall("naming", assignAll());
        stubCall("verify_naming", sameAnswers(false));
        stubCall("naming_new", NAMED);
        long started = System.nanoTime();

        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        var update = nightlyRun(projectId);
        var requests = requests();
        assertThat(requests).extracting(request -> request.path("request_id").asText()).containsExactly(
                update.runId() + ":naming", update.runId() + ":verify_naming", update.runId() + ":naming_new");
        var run = opikConfig.getTopics().getRun();
        assertDeadline(requests.get(0), run.getNamingDeadline(), elapsedMillis);
        assertDeadline(requests.get(1), run.getVerifyDeadline(), elapsedMillis);
        assertDeadline(requests.get(2), run.getNamingDeadline(), elapsedMillis);
        assertThat(update.stats().path("llm_calls").asInt()).isEqualTo(3);
        assertThat(data.topics(projectId)).filteredOn(topic -> topic.name().equals(NEW_THEME)).singleElement()
                .satisfies(topic -> assertThat(topic.born()).isEqualTo(today()));
    }

    @Test
    @DisplayName("a window over maxVectors: the night groups its sample, then its labelling pass gives every trace of "
            + "the window a topic, and marks the labels complete")
    void nightOnASample() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var row = data.topicProject(projectId);
        plant(workspaceId, projectId);
        stubNightly(NAMED, sameAnswers());
        var run = opikConfig.getTopics().getRun();
        int maxVectors = run.getMaxVectors();
        run.setMaxVectors(100);
        NightResult result;
        try {
            result = runNight(Instant.now());
        } finally {
            run.setMaxVectors(maxVectors);
        }

        assertThat(result.saved()).isEqualTo(1);
        var update = nightlyRun(projectId);
        var stats = update.stats();
        int windowTraces = stats.path("window_traces").asInt();
        assertThat(windowTraces).isGreaterThan(100);
        assertThat(stats.path("sample_traces").asInt()).isEqualTo(100);
        assertSavedNight(projectId, row, update.runId(), todayStart(), today());
        // The pass labels the whole window, not only the run's sample.
        assertThat(windowLabels(workspaceId, projectId)).hasSize(windowTraces);
    }

    /** The latest labels of the traces in today's window; older traces keep the labels of earlier runs. */
    private List<TraceTopic> windowLabels(String workspaceId, UUID projectId) {
        var from = todayStart().minus(java.time.Duration.ofDays(opikConfig.getTopics().getRun().getWindowDays()));
        return data.traceTopics(workspaceId, projectId).stream()
                .filter(label -> !label.traceCreatedAt().isBefore(from))
                .toList();
    }

    @Test
    @DisplayName("a failing verify call leaves the groups it checked in Other and the night saves: no second naming "
            + "call, no new topic, the failure in degraded_calls, and failed_runs does not grow")
    void verifyFails() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var topics = data.topics(projectId);
        var row = data.topicProject(projectId);
        plant(workspaceId, projectId);
        stubNightly(assignAll(), null);

        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);

        var update = nightlyRun(projectId);
        assertThat(requests()).extracting(request -> request.path("request_id").asText())
                .contains(update.runId() + ":naming", update.runId() + ":verify_naming")
                .doesNotContain(update.runId() + ":naming_new");
        assertDegraded(update, "verify_naming", TopicProject.RunError.LLM_ERROR);
        assertThat(update.stats().path("forced").path("other_failed").asInt()).isPositive();
        assertThat(data.topics(projectId)).extracting(Topic::id)
                .containsExactlyElementsOf(topics.stream().map(Topic::id).toList());
        assertSavedNight(projectId, row, update.runId(), todayStart(), today());
    }

    @Test
    @DisplayName("a failing naming call leaves the planted groups in Other and the night saves, so failed_runs does "
            + "not grow; the next night, with the naming back, births the planted theme")
    void namingFails() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var topics = data.topics(projectId);
        var row = data.topicProject(projectId);
        plant(workspaceId, projectId);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);

        var update = nightlyRun(projectId);
        assertDegraded(update, "naming", TopicProject.RunError.LLM_ERROR);
        assertThat(update.stats().path("born").asInt()).isZero();
        assertThat(data.topics(projectId)).extracting(Topic::id)
                .containsExactlyElementsOf(topics.stream().map(Topic::id).toList());
        assertSavedNight(projectId, row, update.runId(), todayStart(), today());

        var tomorrow = today().plusDays(1);
        var saved = data.topicProject(projectId);
        stubNightly(NAMED, sameAnswers());

        assertThat(runNight(tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60)).saved()).isEqualTo(1);

        var next = nightlyRun(projectId);
        assertThat(next.stats().has("degraded_calls")).isFalse();
        assertThat(data.topics(projectId)).filteredOn(topic -> topic.name().equals(NEW_THEME)).singleElement()
                .satisfies(topic -> assertThat(topic.born()).isEqualTo(tomorrow));
        assertSavedNight(projectId, saved, next.runId(), tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant(),
                tomorrow);
    }

    @Test
    @DisplayName("a naming call past its deadline leaves the groups in Other and the night saves with llm_timeout in "
            + "degraded_calls")
    void namingTimesOut() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var topics = data.topics(projectId);
        var row = data.topicProject(projectId);
        plant(workspaceId, projectId);
        stubAnswer(NAMED, NAMING_PROMPT, 3_000);
        var run = opikConfig.getTopics().getRun();
        var namingDeadline = run.getNamingDeadline();
        run.setNamingDeadline(Duration.seconds(1));
        NightResult result;
        try {
            result = runNight(Instant.now());
        } finally {
            run.setNamingDeadline(namingDeadline);
        }

        assertThat(result.saved()).isEqualTo(1);
        var update = nightlyRun(projectId);
        assertDegraded(update, "naming", TopicProject.RunError.LLM_TIMEOUT);
        assertThat(data.topics(projectId)).extracting(Topic::id)
                .containsExactlyElementsOf(topics.stream().map(Topic::id).toList());
        assertSavedNight(projectId, row, update.runId(), todayStart(), today());
    }

    @Test
    @DisplayName("a naming answer that is not a JSON object leaves every group in Other and saves")
    void namingAnswerWithoutObject() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
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
        var projectId = discovered(workspaceId);
        plant(workspaceId, projectId);
        stubNightly(NAMED, sameAnswers());
        var debugged = Stream.of(TopicsRunService.class, TopicRunOutcomes.class, TopicsLlm.class)
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
            + "project has no list, so a discovery run makes it again with new topic ids")
    void allRetiredIsDiscoveredAgain() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var row = data.topicProject(projectId);
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
        var cleared = row.toBuilder().savedRuns(row.savedRuns() + 1).listRunId(null).windowUntil(null)
                .labelsComplete(false).labelsFingerprint(null).otherVectors(null).embedModel(null).runId(update.runId())
                .runMode(TopicProject.RunMode.UPDATE).runStatus(TopicProject.RunStatus.SAVED).lastNight(today())
                .build();
        assertThat(data.topicProject(projectId)).usingRecursiveComparison().ignoringFields(SAVED_FIELDS)
                .isEqualTo(cleared);
        assertThat(labels(workspaceId, projectId, update.runId())).isEmpty();
        assertThat(data.traceTopics(workspaceId, projectId))
                .allSatisfy(label -> assertThat(label.runId()).isEqualTo(row.listRunId()));

        var rediscovered = discoverByHand(workspaceId, projectId, today());

        assertSavedNight(projectId, cleared.toBuilder().embedModel(row.embedModel()).build(), rediscovered,
                todayStart(), today());
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
        var projectId = ID_GENERATOR.generateId();
        // The discovery run's window ends three days back; the three nights' windows hold the traces after it.
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), todayStart());
        discoverByHand(workspaceId, projectId, today().minusDays(3));
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
            var date = today().minusDays(3 - night);
            // The night's start: its midnight fire, a minute in.
            var start = date.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60);
            var row = data.topicProject(projectId);

            assertThat(runNight(start).saved()).as("night %d", night).isEqualTo(1);

            assertThat(planner.plan(start.plusSeconds(60)).block()).as("night %d", night).isZero();
            var update = nightlyRun(projectId);
            assertSavedNight(projectId, row, update.runId(), date.atStartOfDay(ZoneOffset.UTC).toInstant(), date);
            var saved = data.topics(projectId).stream().filter(topic -> topic.id().equals(tire.id())).findFirst();
            if (night < 3) {
                assertThat(saved).as("night %d", night).hasValueSatisfying(
                        topic -> assertThat(topic.lowNights()).isEqualTo(data.topicProject(projectId).savedRuns() - 1));
            } else {
                assertThat(saved).isEmpty();
                assertThat(retired(update.runId())).containsEntry(tire.id(), "low_share");
            }
        }
        assertThat(data.topicProject(projectId).savedRuns()).isEqualTo(4);
    }

    @Test
    @DisplayName("a night whose labelling pass did not finish, with the project's trace_topics rows lost, is finished "
            + "by a later plan's labels run: every window trace gets a row of the run and its labels are marked "
            + "complete")
    void nightAfterLostInsert() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        stubNightly(emptyNaming(), sameAnswers());
        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);
        var lost = nightlyRun(projectId);
        // As after inserts out of retries and a pass cut by its deadline: no topic left, labels not complete.
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement(
                "ALTER TABLE trace_topics DELETE WHERE project_id = '%s' SETTINGS mutations_sync = 2"
                        .formatted(projectId))
                .execute()).flatMap(result -> Mono.from(result.getRowsUpdated()))).block();
        labelsIncomplete(projectId);
        // The delete is a mutation: wait until it is applied.
        Awaitility.await().atMost(30, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(data.traceTopics(workspaceId, projectId)).isEmpty());
        var row = data.topicProject(projectId);

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);

        Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> {
            var project = data.topicProject(projectId);
            assertThat(project.runId()).isNotEqualTo(row.runId());
            assertThat(project).usingRecursiveComparison().ignoringFields(RUN_FIELDS)
                    .isEqualTo(row.toBuilder().labelsComplete(true).build());
        });
        assertThat(data.topicProject(projectId).listRunId()).isEqualTo(lost.runId());
        assertThat(labels(workspaceId, projectId, lost.runId()))
                .hasSize(lost.stats().path("window_traces").asInt());
    }

    @Test
    @DisplayName("after a nightly update that retires a topic, the labelling pass also labels a trace vectored after "
            + "the window's end and before the save: its arrival row of the retired topic gets a row of the night's "
            + "run")
    void passLabelsUpToTheSave() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var row = data.topicProject(projectId);
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
                .runId(row.listRunId())
                .build())).block();
        stubNightly(emptyNaming(), sameAnswers());

        assertThat(runNight(Instant.now()).saved()).isEqualTo(1);

        var update = nightlyRun(projectId);
        assertThat(retired(update.runId())).containsOnlyKeys(retiring.id());
        assertThat(data.topics(projectId)).extracting(Topic::id).doesNotContain(retiring.id());
        assertThat(data.traceTopics(workspaceId, projectId)).filteredOn(label -> label.traceId().equals(traceId))
                .singleElement().satisfies(label -> {
                    assertThat(label.runId()).isEqualTo(update.runId());
                    assertThat(label.source()).isEqualTo(TraceTopic.Source.RUN);
                    assertThat(label.topicId()).isNotEqualTo(retiring.id());
                });
        assertSavedNight(projectId, row, update.runId(), todayStart(), today());
    }

    @Test
    @DisplayName("a nightly update whose project another run saved meanwhile fails with save and changes nothing")
    void nightlyUpdateSavedMeanwhile() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var topics = data.topics(projectId);
        var row = data.topicProject(projectId);
        var labels = data.traceTopics(workspaceId, projectId);
        plant(workspaceId, projectId);
        stubAnswer(NAMED, NAMING_PROMPT, 3_000);
        stubAnswer(sameAnswers(), VERIFY_PROMPT, 0);

        var queued = plan(Instant.now());
        assertThat(queued).containsOnlyKeys(projectId);
        // While the LLM names the groups, another run saves the project.
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1));
        var other = ID_GENERATOR.generateId();
        int saved = template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class).saveList(workspaceId,
                projectId, row.listRunId(), other, Instant.now(), row.windowUntil(), row.otherVectors(),
                row.embedModel()));
        assertThat(saved).isEqualTo(1);
        awaitEnded(projectId, queued.get(projectId));

        var update = nightlyRun(projectId);
        // A save error's message is never logged: it can quote trace text. The failed line names the code.
        assertThat(runLog(update.runId())).anyMatch(line -> line.contains("failed") && line.contains("code 'save'"));
        var failed = failedNight(row, update.runId(), TopicProject.RunError.SAVE).toBuilder()
                .savedRuns(row.savedRuns() + 1).listRunId(other).labelsComplete(false).build();
        assertThat(data.topicProject(projectId)).usingRecursiveComparison()
                .ignoringFields(Stream.concat(Stream.of(FAILED_FIELDS), Stream.of("lastSuccessAt"))
                        .toArray(String[]::new))
                .isEqualTo(failed);
        assertThat(data.topics(projectId)).isEqualTo(topics);
        assertThat(data.traceTopics(workspaceId, projectId)).containsExactlyInAnyOrderElementsOf(labels);
    }

    @Test
    @DisplayName("a nightly update whose window traces are deleted while it names writes no trace_topics row for them")
    void tracesDeletedDuringTheNight() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var row = data.topicProject(projectId);
        plant(workspaceId, projectId);
        // Traces of the night's window, which starts windowDays before today.
        var windowFrom = todayStart().minus(opikConfig.getTopics().getRun().getWindowDays(), ChronoUnit.DAYS);
        var deleted = data.traceTopics(workspaceId, projectId).stream()
                .filter(label -> !label.traceCreatedAt().isBefore(windowFrom)).map(TraceTopic::traceId).distinct()
                .limit(20).collect(Collectors.toUnmodifiableSet());
        assertThat(deleted).hasSize(20);
        stubAnswer(NAMED, NAMING_PROMPT, 3_000);
        stubAnswer(sameAnswers(), VERIFY_PROMPT, 0);
        int generatesBefore = wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH))).size();

        var queued = plan(Instant.now());
        assertThat(queued).containsOnlyKeys(projectId);
        // The night has read its window; while the LLM names the groups, the delete cascade runs for 20 of its traces.
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(generatesBefore + 1));
        vectorsDAO.deleteByTraceIds(deleted, projectId)
                .then(traceTopicsDAO.deleteByTraceIds(deleted, projectId))
                .contextWrite(context -> context.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, "test"))
                .block();
        // The delete ran before the night's labelling pass.
        assertThat(labels(workspaceId, projectId, queued.get(projectId))).isEmpty();
        awaitEnded(projectId, queued.get(projectId));

        var update = nightlyRun(projectId);
        assertThat(update.status()).isEqualTo(TopicProject.RunStatus.SAVED);
        int windowTraces = update.stats().path("window_traces").asInt();
        assertSavedNight(projectId, row, update.runId(), todayStart(), today());
        assertThat(data.traceTopics(workspaceId, projectId)).noneMatch(label -> deleted.contains(label.traceId()));
        assertThat(windowLabels(workspaceId, projectId)).hasSize(windowTraces - deleted.size());
    }

    @Test
    @DisplayName("a run that outlasts projectTimeout fails with timeout and keeps the previous list; the night's other "
            + "project still saves")
    void projectTimeout() {
        // One project per workspace: the generate call names its workspace, so the slow answer is bound to one project.
        var slowWorkspace = enabledWorkspace();
        var fastWorkspace = enabledWorkspace();
        var timedOut = discovered(slowWorkspace);
        var saved = discovered(fastWorkspace);
        var timedOutRow = data.topicProject(timedOut);
        var savedRow = data.topicProject(saved);
        var topics = data.topics(timedOut);
        plant(slowWorkspace, timedOut);
        plant(fastWorkspace, saved);
        // Every call of the slow workspace answers after the project timeout; every other call answers at once.
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).atPriority(1)
                .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(slowWorkspace)))
                .willReturn(TopicsTestData.answer(NAMED, 4_000)));
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).atPriority(2).willReturn(TopicsTestData.answer(NAMED, 0)));
        var run = opikConfig.getTopics().getRun();
        var projectTimeout = run.getProjectTimeout();
        run.setProjectTimeout(Duration.seconds(2));
        NightResult result;
        try {
            result = runNight(Instant.now());
            // The cut update may still get its slow answer: past it, a save that raced the timeout would show.
            Awaitility.await().pollDelay(3, TimeUnit.SECONDS).atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
                var update = nightlyRun(timedOut);
                assertThat(runLog(update.runId())).anyMatch(line -> line.contains("projectTimeout"));
                assertFailedNight(timedOut, timedOutRow, update.runId(), TopicProject.RunError.TIMEOUT);
                assertThat(data.topics(timedOut)).isEqualTo(topics);
                assertThat(labels(slowWorkspace, timedOut, update.runId())).isEmpty();
            });
        } finally {
            run.setProjectTimeout(projectTimeout);
        }

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        // The 2 s timeout also cut the saved project's labelling pass; the next plan resumes it.
        planner.plan(Instant.now()).block();
        assertSavedNight(saved, savedRow, nightlyRun(saved).runId(), todayStart(), today());
    }

    @Test
    @DisplayName("the night endpoint queues the project's nightly update (202 with its run id) and a pod saves it, "
            + "with no night field written; it answers 409 while the run is in flight, for a project with no list, "
            + "once the night is saved and with topics off, and 400 for a night that is not a date")
    void nightEndpoint() {
        var workspaceId = enabledWorkspace();
        var projectId = discovered(workspaceId);
        var row = data.topicProject(projectId);
        var listless = ID_GENERATOR.generateId();
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
        // One project's run, not a night: the night fields stay.
        assertSavedNight(projectId, row, runId, todayStart(), row.lastNight());
        try (var response = nightEndpoint(workspaceId, projectId, today().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains(skippedByHand(projectId, "already_saved"));
        }
        opikConfig.getTopics().setEnabled(false);
        try (var response = nightEndpoint(workspaceId, projectId, today().plusDays(1).toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        } finally {
            opikConfig.getTopics().setEnabled(true);
        }
    }

    /** A pass of its own, to read its metrics; its queue is the caller's, the night's end key in Redis the app's. */
    private TopicsNightPass pass(InMemoryMetricReader reader) {
        return pass(candidates, reader);
    }

    private TopicsNightPass pass(TopicsDiscoveryCandidates candidates, InMemoryMetricReader reader) {
        return new TopicsNightPass(opikConfig.getTopics(), template, workspaceService, candidates,
                TopicsMetricsReader.metrics(reader), workspaceNames, projectService, lockService);
    }

    /** A queue that records what it is given and returns a new run id. */
    private static Function<PlannedRun, Mono<UUID>> capture(List<PlannedRun> queued) {
        return planned -> {
            queued.add(planned);
            return Mono.just(ID_GENERATOR.generateId());
        };
    }

    /** A project with {@code traces} vectors in tonight's window and a list saved for the night before. */
    private UUID seededList(String workspaceId, int traces) {
        return seededList(workspaceId, traces, 1);
    }

    /** As {@link #seededList(String, int)}, with a list saved {@code nightsAgo} nights before tonight. */
    private UUID seededList(String workspaceId, int traces, int nightsAgo) {
        var projectId = ID_GENERATOR.generateId();
        data.seed(workspaceId, projectId, 0, traces, todayStart());
        listed(workspaceId, projectId, nightsAgo);
        return projectId;
    }

    /** {@code runs} update runs of the project that failed on the nights before, the last one yesterday. */
    private void failRuns(String workspaceId, UUID projectId, int runs) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            for (int i = 0; i < runs; i++) {
                var runId = ID_GENERATOR.generateId();
                var at = yesterdayStart().plusSeconds(i);
                TopicsTestData.startRun(dao, workspaceId, projectId, runId, TopicProject.RunMode.UPDATE, at);
                dao.markFailed(workspaceId, projectId, runId, at, TopicProject.RunError.LLM_ERROR);
            }
            return null;
        });
    }

    /** A project of the fixture's traces with a list from a real discovery run for yesterday, its labels complete. */
    private UUID discoveredYesterday(String workspaceId) {
        var projectId = ID_GENERATOR.generateId();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), yesterdayStart());
        discoverByHand(workspaceId, projectId, today().minusDays(1));
        return projectId;
    }

    /**
     * A project of {@value #BUILT} traces with a list from a discovery run by hand for yesterday, its labels complete;
     * the WireMock stubs and requests are cleared.
     */
    private UUID discovered(String workspaceId) {
        var projectId = ID_GENERATOR.generateId();
        data.seed(workspaceId, projectId, 0, BUILT, yesterdayStart());
        discoverByHand(workspaceId, projectId, today().minusDays(1));
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
        wireMock.resetAll();
        return projectId;
    }

    /** The project's discovery run by hand for {@code night}; returns its run once it and its labels run ended. */
    private UUID discoverByHand(String workspaceId, UUID projectId, LocalDate night) {
        stubAnswer(FIXTURE.discoveryAnswer);
        var runId = planner.runByHand(workspaceId, projectId, night, true).block();
        Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(data.topicProject(projectId))
                        .satisfies(project -> {
                            assertThat(project.listRunId()).isEqualTo(runId);
                            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
                            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
                        }));
        return runId;
    }

    /** A saved list with its window until yesterday's start, its labels complete: no run of its own is due. */
    private void listedYesterday(String workspaceId, UUID projectId) {
        listed(workspaceId, projectId, 1);
    }

    /** A saved list with its window until {@code nightsAgo} nights before tonight's start, its labels complete. */
    private void listed(String workspaceId, UUID projectId, int nightsAgo) {
        data.savedList(workspaceId, projectId, todayStart().minusSeconds(86_400L * nightsAgo), FIXTURE.size());
        store.markLabelsComplete(workspaceId, projectId, data.topicProject(projectId).listRunId(), "test").block();
    }

    /**
     * Marks the project's labels not complete, as a labelling pass cut by its deadline leaves them. Written to the row
     * directly: only a save sets it so, and a save would write a new list.
     */
    private void labelsIncomplete(UUID projectId) {
        template.inTransaction(WRITE, handle -> handle.execute(
                "UPDATE topic_projects SET labels_complete = FALSE WHERE project_id = ?", projectId.toString()));
    }

    /**
     * The project's row is {@code before} after a saved nightly update {@code runId} and its labelling pass: one more
     * save, the window until {@code windowUntil}, the labels complete and the night {@code lastNight}.
     */
    private void assertSavedNight(UUID projectId, TopicProject before, UUID runId, Instant windowUntil,
            LocalDate lastNight) {
        var expected = before.toBuilder().savedRuns(before.savedRuns() + 1).windowUntil(windowUntil)
                .labelsComplete(true).runMode(TopicProject.RunMode.LABELS).runStatus(TopicProject.RunStatus.SAVED)
                .lastNight(lastNight).build();
        // The labelling pass is a run of its own, queued after the save; it starts once arrival can't still be
        // labelling by the list before.
        Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                data.topicProject(projectId)).usingRecursiveComparison().ignoringFields(RUN_FIELDS)
                .isEqualTo(expected));
        assertThat(data.topicProject(projectId).listRunId()).isEqualTo(runId);
    }

    /** The night saved with one failed call, {@code call}, and it says so in its stats and its WARN line. */
    private void assertDegraded(NightlyRun update, String call, TopicProject.RunError code) {
        assertThat(update.stats().path("degraded_calls")).singleElement().satisfies(entry -> {
            assertThat(entry.path("call").asText()).isEqualTo(call);
            assertThat(entry.path("error_code").asText()).isEqualTo(code.getValue());
        });
        assertThat(runLog(update.runId())).anyMatch(line -> line.contains("call '" + call + "' failed")
                && line.contains("error code '" + code.getValue() + "'"));
    }

    /** opik-inference's answer that breaks the schema: the call fails with {@code llm_bad_answer}. */
    private static ResponseDefinitionBuilder schemaViolation() {
        return aResponse().withStatus(500).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"schema_violation\",\"message\":\"m\",\"retryable\":true}}");
    }

    /** The project's row is {@code before} after tonight's nightly update {@code runId} failed with {@code code}. */
    private void assertFailedNight(UUID projectId, TopicProject before, UUID runId, TopicProject.RunError code) {
        var row = data.topicProject(projectId);
        assertThat(row).usingRecursiveComparison().ignoringFields(FAILED_FIELDS)
                .isEqualTo(failedNight(before, runId, code));
        // The pause counts from the last failure: the failed run's end.
        assertThat(row.lastFailedAt()).isEqualTo(row.runFinishedAt());
    }

    /** {@code before} after tonight's nightly update {@code runId} failed with {@code code}: the list stays. */
    private static TopicProject failedNight(TopicProject before, UUID runId, TopicProject.RunError code) {
        return before.toBuilder().runId(runId).runMode(TopicProject.RunMode.UPDATE)
                .runStatus(TopicProject.RunStatus.FAILED).runErrorCode(code).failedRuns(before.failedRuns() + 1)
                .lastNight(today()).build();
    }

    /** One plan at {@code start}, then each nightly update it queued and the labels run that follows a save. */
    private NightResult runNight(Instant start) {
        var queued = plan(start);
        queued.forEach(this::awaitEnded);
        return new NightResult(queued.keySet().stream().map(this::nightlyRun).toList());
    }

    /** One plan at {@code start}; the nightly updates it queued, by project, from the night's queue lines. */
    private Map<UUID, UUID> plan(Instant start) {
        int from = logEvents().size();
        planner.plan(start).block();
        var events = logEvents();
        var queued = events.subList(from, events.size()).stream()
                .filter(event -> event.getMessage().startsWith("Topics night '{}' queued run '{}'"))
                .collect(Collectors.toMap(event -> (UUID) event.getArgumentArray()[3],
                        event -> (UUID) event.getArgumentArray()[1]));
        nightlyRuns.putAll(queued);
        return queued;
    }

    /**
     * Until the run failed, or it saved and the labels run it queued ended. A save that cleared the list queues no
     * labels run.
     */
    private void awaitEnded(UUID projectId, UUID runId) {
        Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> assertThat(
                data.topicProject(projectId)).satisfies(project -> {
                    if (runId.equals(project.runId())) {
                        assertThat(project.runStatus() == TopicProject.RunStatus.FAILED
                                || project.runStatus() == TopicProject.RunStatus.SAVED && project.listRunId() == null)
                                .isTrue();
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
    private record NightlyRun(UUID runId, TopicProject.RunStatus status, JsonNode stats) {
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
            return new NightlyRun(runId, TopicProject.RunStatus.SAVED, stats(runId));
        }
        assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.UPDATE);
        return new NightlyRun(runId, project.runStatus(), stats(runId));
    }

    /** The test's captured log lines, copied under the appender's lock: runs log from their own threads. */
    private List<ILoggingEvent> logEvents() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    /** The stats of the run's {@code saved} or {@code failed} line; null when it logged none. */
    private JsonNode stats(UUID runId) {
        return logEvents().stream().filter(event -> isRun(event, runId) && event.getMessage().contains("stats '{}'"))
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

    private static boolean isRun(ILoggingEvent event, UUID runId) {
        return event.getMessage().startsWith("Topics run '{}'") && event.getArgumentArray() != null
                && runId.equals(event.getArgumentArray()[0]);
    }

    /** The trace_topics rows the run wrote. */
    private List<TraceTopic> labels(String workspaceId, UUID projectId, UUID runId) {
        return data.traceTopics(workspaceId, projectId).stream()
                .filter(label -> runId.equals(label.runId())).toList();
    }

    /** The {@value #PLANTED} traces of the nightly fixture's theme, created yesterday. */
    private void plant(String workspaceId, UUID projectId) {
        plant(workspaceId, projectId, 0, PLANTED, today().minusDays(1));
    }

    /** The nightly fixture's traces {@code [from, from + count)}, created on {@code day}, a minute apart. */
    private void plant(String workspaceId, UUID projectId, int from, int count, LocalDate day) {
        int d = TopicsNightlyFixture.DIMENSIONS;
        var dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        var rows = new ArrayList<TopicVector>(count);
        for (int i = from; i < from + count; i++) {
            rows.add(TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .task(TopicEmbedding.SUMMARY_TASK)
                    .traceId(ID_GENERATOR.generateId())
                    .traceCreatedAt(dayStart.plusSeconds(60L * i))
                    .taskVersion(1)
                    .embedModel(TopicEmbedding.MODEL)
                    .embedDimensions(TopicEmbedding.DIMENSIONS)
                    .text(NIGHTLY.intents[i])
                    .vector(Arrays.copyOfRange(NIGHTLY.vectors, i * d, (i + 1) * d))
                    .build());
        }
        vectorsDAO.insert(rows).block();
    }

    /** The vector of a trace created after tonight's window ends (today's start) and before now, the save. */
    private TopicVector vectorAfterTheWindow(String workspaceId, UUID projectId) {
        var start = todayStart();
        var row = TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(TopicEmbedding.SUMMARY_TASK)
                .traceId(ID_GENERATOR.generateId())
                .traceCreatedAt(start.plusMillis((Instant.now().toEpochMilli() - start.toEpochMilli()) / 2))
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

    /**
     * Points the topics' prototypes along an axis no trace is near, so they hold no trace on the next night. Written
     * through the DAO: no API moves a topic's prototypes.
     */
    private void moveAway(List<Topic> topics) {
        template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class).updateTopics(topics.stream()
                .map(topic -> topic.toBuilder()
                        .vectors(new PrototypeVectors(List.of(TopicsTestData.unit(TopicEmbedding.DIMENSIONS - 1))))
                        .build())
                .toList()));
    }

    private Response nightEndpoint(String workspaceId, UUID projectId, String night) {
        return client.target("%s/v1/internal/topics/projects/%s/night".formatted(baseUrl, projectId))
                .queryParam("workspace_id", workspaceId)
                .queryParam("night", night)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    /** The generate requests WireMock got, in the order it got them. */
    private List<JsonNode> requests() {
        return wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH))).stream()
                .map(request -> JsonUtils.getJsonNodeFromString(request.getBodyAsString()))
                .toList();
    }

    /** The request's deadline is its kind's, less at most the time the night took. */
    private static void assertDeadline(JsonNode request, Duration expected, long elapsedMillis) {
        assertThat(request.path("deadline_ms").asLong())
                .isBetween(expected.toMilliseconds() - elapsedMillis, expected.toMilliseconds());
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

    /** Answers {@code output} to the call whose request id ends with {@code :tag}, whatever its run. */
    private void stubCall(String tag, JsonNode output) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH))
                .withRequestBody(matchingJsonPath("$.request_id", matching(".*:" + tag)))
                .willReturn(TopicsTestData.answer(output, 0)));
    }

    /** Answers {@code output} after {@code delayMillis} to the calls whose body contains {@code prompt}. */
    private void stubAnswer(JsonNode output, String prompt, int delayMillis) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).withRequestBody(containing(prompt))
                .willReturn(TopicsTestData.answer(output, delayMillis)));
    }

    private void stubAnswer(JsonNode answer) {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(answer, 0)));
    }

    /** A verify answer that finds every pair the same topic. */
    private static JsonNode sameAnswers() {
        return sameAnswers(true);
    }

    /** A verify answer of {@code same} for every pair the night can have. */
    private static JsonNode sameAnswers(boolean same) {
        var answer = JsonUtils.createObjectNode();
        var answers = answer.putArray("answers");
        for (int pair = 0; pair < 20; pair++) {
            answers.addObject().put("pair", pair).put("same", same);
        }
        return answer;
    }

    /** A naming answer that assigns every group the night can have to the prompt's first live topic. */
    private static JsonNode assignAll() {
        var naming = JsonUtils.createObjectNode();
        var assign = naming.putArray("assign");
        for (int group = 0; group < 20; group++) {
            assign.addObject().put("cluster", group).put("topic", 1);
        }
        naming.putArray("new");
        naming.putArray("other");
        return naming;
    }

    /** A naming answer with every group the night can have as one new topic; it keeps only the night's groups. */
    private static JsonNode named() {
        var answer = JsonUtils.createObjectNode();
        answer.putArray("assign");
        var clusters = answer.putArray("new").addObject().put("name", NEW_THEME)
                .put("definition", "Asks whether the car's tires need air.").putArray("clusters");
        for (int group = 0; group < 100; group++) {
            clusters.add(group);
        }
        answer.putArray("other");
        return answer;
    }

    /** A naming answer that changes nothing. */
    private static JsonNode emptyNaming() {
        var naming = JsonUtils.createObjectNode();
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
     * so a discovery run by hand does not wait.
     */
    private String enabledWorkspace() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        var topics = opikConfig.getTopics();
        var listed = new TreeSet<>(topics.enabledWorkspaceIds());
        listed.add(workspaceId);
        topics.setEnabledWorkspaces(String.join(",", listed));
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).checked(workspaceId, now);
            var dao = handle.attach(TopicProjectDAO.class);
            var projectId = ID_GENERATOR.generateId();
            dao.startBackfills(List.of(TopicBackfill.builder().workspaceId(workspaceId)
                    .projectId(projectId).backfillStartedAt(now).backfillLastDoneAt(now).build()));
            dao.recordBackfills(List.of(TopicBackfillProgress.builder().workspaceId(workspaceId)
                    .projectId(projectId).drainedAt(now).build()));
            return null;
        });
        return workspaceId;
    }
}
