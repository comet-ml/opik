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
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TopicsRunPlanner planner;
    private TopicsTestData data;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicsRunPlanner planner,
            @Jit TopicVectorsDAO vectorsDAO) {
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

        var discoveryLists = Map.of(first, data.topicProject(first).listRunId(), second,
                data.topicProject(second).listRunId());

        // At least the two updates: a pod can save one before this plan's labels pass reads, which then queues its
        // labelling pass too. The rows below show what the night decided.
        assertThat(planner.plan(Instant.now()).block()).isGreaterThanOrEqualTo(2);

        for (var projectId : List.of(first, second)) {
            Awaitility.await().atMost(WAIT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(data.topicProject(projectId))
                            .satisfies(project -> {
                                assertThat(project.listRunId()).isNotEqualTo(discoveryLists.get(projectId));
                                assertThat(project.lastNight()).isEqualTo(today());
                                assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
                                assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
                                assertThat(project.labelsComplete()).isTrue();
                            }));
            assertThat(data.topicProject(projectId).windowUntil()).isEqualTo(todayStart());
        }
        assertThat(data.topicProject(small)).satisfies(project -> {
            assertThat(project.lastNight()).isEqualTo(today());
            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.DISCOVERY);
        });

        assertThat(planner.plan(Instant.now()).block()).isZero();
        for (var projectId : List.of(first, second)) {
            assertThat(data.topicProject(projectId).savedRuns()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("after the budget no nightly update is queued: each project with a list the night did not look at "
            + "has its not-reached streak counted once, however many plans run; a project the night looked at keeps 0; "
            + "the night is counted finished once")
    void budgetCutOff(@Jit TopicWorkspaceService workspaceService, @Jit TopicsDiscoveryCandidates candidates,
            @Jit WorkspaceNameService workspaceNames, @Jit ProjectService projectService,
            @Jit LockService lockService) {
        var workspaceId = enabledWorkspace();
        var notReached = ID_GENERATOR.generateId();
        var looked = ID_GENERATOR.generateId();
        for (var projectId : List.of(notReached, looked)) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), todayStart());
            listedYesterday(workspaceId, projectId);
        }
        template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class)
                .markLooked(workspaceId, List.of(looked), today()));
        opikConfig.getTopics().getNightly().setBudget(Duration.milliseconds(1));
        // Its own pass, to read its metrics; the night's end key in Redis is the app's.
        var reader = InMemoryMetricReader.create();
        var pass = new TopicsNightPass(opikConfig.getTopics(), template, workspaceService, candidates,
                TopicsMetricsReader.metrics(reader), workspaceNames, projectService, lockService);

        assertThat(pass.plan(Instant.now(), 10, planned -> Mono.empty()).block()).isZero();
        assertThat(pass.plan(Instant.now(), 10, planned -> Mono.empty()).block()).isZero();

        assertThat(data.topicProject(notReached)).satisfies(project -> {
            assertThat(project.notReachedNights()).isEqualTo(1);
            assertThat(project.lastNight()).isNull();
            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.DISCOVERY);
        });
        assertThat(data.topicProject(looked).notReachedNights()).isZero();
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
