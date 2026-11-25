package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.lock.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The night pass of the planner as a black box, against real MySQL, ClickHouse and Redis, with opik-inference's
 * {@code /v1/generate} stubbed in WireMock. The night starts at today's midnight (UTC); each test sets its budget. A
 * project gets its list from a real discovery run for yesterday, so tonight's window holds the same traces.
 */
@DisplayName("topics nightly updates on the run queue")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsNightlyIntegrationTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int SMALL = 50;
    private static final long WAIT_SECONDS = 90;

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
    private static final String[] RUN_FIELDS = {"runId", "listRunId", "otherVectors", "lastSuccessAt",
            "runStartedAt", "runFinishedAt", "runQueuedAt", "deadlineAt", "heartbeatAt"};

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TopicsRunPlanner planner;
    private TopicsTestData data;
    private TopicRunStore store;
    private TopicWorkspaceService workspaceService;
    private TopicsDiscoveryCandidates candidates;
    private WorkspaceNameService workspaceNames;
    private ProjectService projectService;
    private LockService lockService;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicsRunPlanner planner,
            @Jit TopicVectorsDAO vectorsDAO, @Jit TopicRunStore store, @Jit TopicWorkspaceService workspaceService,
            @Jit TopicsDiscoveryCandidates candidates, @Jit WorkspaceNameService workspaceNames,
            @Jit ProjectService projectService, @Jit LockService lockService) {
        this.store = store;
        this.workspaceService = workspaceService;
        this.candidates = candidates;
        this.workspaceNames = workspaceNames;
        this.projectService = projectService;
        this.lockService = lockService;
        this.opikConfig = opikConfig;
        this.template = template;
        this.planner = planner;
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
    }

    @Test
    @DisplayName("two plans of one night queue one nightly update per project with a list, and skip one under "
            + "minVectors; a pod saves each update and then runs its labelling pass; a plan after that queues nothing")
    void oneNightlyUpdatePerProjectPerNight() {
        var workspaceId = enabledWorkspace();
        var first = builtYesterday(workspaceId);
        var second = builtYesterday(workspaceId);
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
            assertThat(data.topicProject(projectId).listRunId()).isNotEqualTo(before.get(projectId).listRunId());
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
        var queued = new ArrayList<TopicsRunPlanner.Planned>();

        assertThat(pass(reader).plan(Instant.now(), 10, capture(queued)).block()).isEqualTo(1);

        assertThat(queued).containsExactly(new TopicsRunPlanner.Planned(workspaceId, built,
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
        var queued = new ArrayList<TopicsRunPlanner.Planned>();

        assertThat(pass(reader).plan(Instant.now(), 0, capture(queued)).block()).isZero();
        assertThat(data.topicProject(gone).lastNight()).isEqualTo(today());
        assertThat(queued).isEmpty();

        assertThat(pass(reader).plan(Instant.now(), 1, planned -> Mono.empty()).block()).isZero();
        assertThat(data.topicProject(older).lastNight()).isNull();

        assertThat(pass(reader).plan(Instant.now(), 1, capture(queued)).block()).isEqualTo(1);
        assertThat(queued).extracting(TopicsRunPlanner.Planned::projectId).containsExactly(older);
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
        var reader = InMemoryMetricReader.create();
        var pass = pass(reader);

        assertThat(pass.plan(Instant.now(), 10, planned -> Mono.empty()).block()).isZero();
        assertThat(pass.plan(Instant.now(), 10, planned -> Mono.empty()).block()).isZero();

        assertThat(data.topicProject(notReached))
                .isEqualTo(notReachedBefore.toBuilder().notReachedNights(1).build());
        assertThat(data.topicProject(looked)).isEqualTo(lookedBefore);
        assertThat(TopicsMetricsReader.sum(reader, "topics.nightly.nights", "outcome", "finished")).isEqualTo(1);
    }

    @Test
    @DisplayName("a workspace listed at stage vectors gets no nightly update; one listed at nightly gets its update")
    void stageBeforeNightlyIsSkipped() {
        var atNightly = enabledWorkspace();
        var projectId = builtYesterday(atNightly);
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
    }

    /** A pass of its own, to read its metrics; its queue is the caller's, the night's end key in Redis the app's. */
    private TopicsNightPass pass(InMemoryMetricReader reader) {
        return new TopicsNightPass(opikConfig.getTopics(), template, workspaceService, candidates,
                TopicsMetricsReader.metrics(reader), workspaceNames, projectService, lockService);
    }

    /** A queue that records what it is given and returns a new run id. */
    private static Function<TopicsRunPlanner.Planned, Mono<UUID>> capture(List<TopicsRunPlanner.Planned> queued) {
        return planned -> {
            queued.add(planned);
            return Mono.just(ID_GENERATOR.generateId());
        };
    }

    /** A project with {@code traces} vectors in tonight's window and a list saved for the night before. */
    private UUID seededList(String workspaceId, int traces) {
        var projectId = ID_GENERATOR.generateId();
        data.seed(workspaceId, projectId, 0, traces, todayStart());
        listedYesterday(workspaceId, projectId);
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

    /** A project with a list from a real discovery run for yesterday, its labels complete. */
    private UUID builtYesterday(String workspaceId) {
        var projectId = ID_GENERATOR.generateId();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), yesterdayStart());
        var runId = planner.runByHand(workspaceId, projectId, today().minusDays(1), true).block();
        Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(data.topicProject(projectId))
                        .satisfies(project -> {
                            assertThat(project.listRunId()).isEqualTo(runId);
                            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
                            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
                        }));
        return projectId;
    }

    /** A saved list with its window until yesterday's start, its labels complete: no run of its own is due. */
    private void listedYesterday(String workspaceId, UUID projectId) {
        data.savedList(workspaceId, projectId, yesterdayStart(), FIXTURE.size());
        store.markLabelsComplete(workspaceId, projectId, data.topicProject(projectId).listRunId()).block();
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
