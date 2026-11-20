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
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.topics.TopicProject.CancelReason;
import com.comet.opik.domain.topics.TopicProject.RunStatus;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.lock.LockService.Lock;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first-build job, the run by hand and the project-delete cancel against real ClickHouse, MySQL and Redis, with
 * opik-inference's {@code /v1/generate} stubbed in WireMock. The planner and the runs themselves are
 * {@link TopicsRunsIntegrationTest}'s. The scheduled job fires at startup, before any workspace is opted in, which
 * holds its plan and sweep locks; a test that fires the job frees the lock it needs first.
 */
@DisplayName("topics first-build job")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsFirstBuildJobIntegrationTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final Lock PLAN_LOCK = new Lock("topics:first_build:plan");
    private static final Lock SWEEP_LOCK = new Lock("topics:first_build:sweep");

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
                        new CustomConfig("topics.firstBuild.enabled", "true"),
                        new CustomConfig("topics.firstBuild.planInterval", "1h"),
                        new CustomConfig("topics.runs.poolingInterval", "200ms"),
                        new CustomConfig("topics.runs.longPollingDuration", "200ms")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TopicsTestData data;
    private GuiceJobManager jobManager;
    private TopicsFirstBuildJob job;
    private LockService lockService;
    private TopicsProjectDeletedListener projectDeletedListener;
    private ClientSupport client;
    private String baseUrl;

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, GuiceJobManager jobManager, LockService lockService,
            @Jit TopicsFirstBuildJob job, @Jit TopicsProjectDeletedListener projectDeletedListener,
            @Jit TopicVectorsDAO vectorsDAO) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.opikConfig = opikConfig;
        this.template = template;
        this.jobManager = jobManager;
        this.job = job;
        this.lockService = lockService;
        this.projectDeletedListener = projectDeletedListener;
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
        // Each test opts in its own workspaces: the earlier ones must not be planned again.
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE");
            return null;
        });
        opikConfig.getTopics().setEnabledWorkspaces("");
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(FIXTURE.firstBuildAnswer,
                0)));
    }

    @Test
    @DisplayName("enabled, the first-build job is scheduled on topics.firstBuild.planInterval")
    void jobIsScheduled() throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var triggers = scheduler.getJobKeys(GroupMatcher.anyGroup()).stream()
                .filter(key -> jobClass(key) == TopicsFirstBuildJob.class)
                .flatMap(key -> triggers(key).stream())
                .toList();

        assertThat(triggers).singleElement().isInstanceOfSatisfying(SimpleTrigger.class,
                trigger -> assertThat(trigger.getRepeatInterval()).isEqualTo(Duration.ofHours(1).toMillis()));
    }

    @Test
    @DisplayName("a job fire plans: the opted-in project's first build is queued and built; another fire within the "
            + "interval plans nothing")
    void jobPlans() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        lockService.unlockUsingToken(PLAN_LOCK).block();

        // A fire can skip while the startup fire still holds the leader lock: fire until one plans.
        Awaitility.await().atMost(WAIT).untilAsserted(() -> {
            job.doJob(null);
            assertThat(data.topicProject(projectId)).isNotNull()
                    .satisfies(project -> assertThat(project.runId()).isNotNull());
        });
        var builtRun = awaitBuilt(projectId);

        var other = UUID.randomUUID();
        data.seed(workspaceId, other, 0, FIXTURE.size(), tomorrowStart());
        job.doJob(null);
        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertNoRun(other));
        assertThat(data.topicProject(projectId).listRunId()).isEqualTo(builtRun);
    }

    @Test
    @DisplayName("a job fire deletes the topics of a project that no longer exists, with their prototype vectors, and "
            + "keeps an existing project's")
    void jobSweepsDeletedProjects() {
        var workspaceId = enabledWorkspace();
        var deleted = UUID.randomUUID();
        var kept = UUID.randomUUID();
        data.project(workspaceId, kept);
        data.savedList(workspaceId, deleted, tomorrowStart(), FIXTURE.size());
        data.savedList(workspaceId, kept, tomorrowStart(), FIXTURE.size());
        lockService.unlockUsingToken(SWEEP_LOCK).block();

        Awaitility.await().atMost(WAIT).untilAsserted(() -> {
            job.doJob(null);
            assertThat(data.topics(deleted)).isEmpty();
        });
        assertThat(data.topics(kept)).singleElement()
                .satisfies(topic -> assertThat(topic.vectors().vectors()).hasSize(1));
    }

    @Test
    @DisplayName("the internal endpoint queues a first build and returns its run id, which a pod builds; a second "
            + "call is refused (409) with the planner's reason, and a bad night is refused (400)")
    void runByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());

        String runId;
        try (var response = runProject(workspaceId, projectId, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
            runId = response.readEntity(JsonNode.class).path("run_id").asText();
        }

        assertThat(awaitBuilt(projectId)).hasToString(runId);
        try (var response = runProject(workspaceId, projectId, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("has a topic list");
        }
        try (var response = runProject(aiLabelsOnlyWorkspace(), projectId, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("not opted in");
        }
        for (var night : List.of("yesterday", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString(),
                "-999999999-01-05")) {
            try (var response = runProject(workspaceId, UUID.randomUUID(), night)) {
                assertThat(response.getStatus()).isEqualTo(400);
            }
        }
    }

    @Test
    @DisplayName("deleting a project cancels its runs: a queued run fails at once as cancelled, a running one goes to "
            + "cancelling; another project's run is kept")
    void projectDeleteCancelsItsRuns() {
        var workspaceId = enabledWorkspace();
        var queued = UUID.randomUUID();
        var running = UUID.randomUUID();
        var kept = UUID.randomUUID();
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            dao.ensureRow(workspaceId, queued);
            dao.queue(workspaceId, queued, TopicsTestData.newId(), TopicProject.RunMode.FIRST_BUILD, now);
            TopicsTestData.startRun(dao, workspaceId, running, TopicsTestData.newId(),
                    TopicProject.RunMode.FIRST_BUILD, now);
            TopicsTestData.startRun(dao, workspaceId, kept, TopicsTestData.newId(),
                    TopicProject.RunMode.FIRST_BUILD, now);
            return null;
        });

        projectDeletedListener.onProjectsDeleted(new ProjectsDeleted(Set.of(queued, running), workspaceId, "test"));

        assertThat(data.topicProject(queued)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(project.runErrorCode()).isEqualTo(TopicProject.RunError.CANCELLED);
            assertThat(project.cancelReason()).isEqualTo(CancelReason.PROJECT_DELETED);
        });
        assertThat(data.topicProject(running)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(RunStatus.CANCELLING);
            assertThat(project.cancelReason()).isEqualTo(CancelReason.PROJECT_DELETED);
        });
        assertThat(data.topicProject(kept).runStatus()).isEqualTo(RunStatus.RUNNING);
    }

    private Class<?> jobClass(org.quartz.JobKey key) {
        try {
            return jobManager.getScheduler().getJobDetail(key).getJobClass();
        } catch (SchedulerException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<? extends org.quartz.Trigger> triggers(org.quartz.JobKey key) {
        try {
            return jobManager.getScheduler().getTriggersOfJob(key);
        } catch (SchedulerException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Waits until the project's first build is saved and returns its run id. */
    private UUID awaitBuilt(UUID projectId) {
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId).listRunId())
                .isNotNull());
        return data.topicProject(projectId).listRunId();
    }

    /** No run was queued for the project: it has no row, or only the vectors tick's backfill columns. */
    private void assertNoRun(UUID projectId) {
        var project = data.topicProject(projectId);
        assertThat(project == null ? null : project.runId()).isNull();
    }

    private Response runProject(String workspaceId, UUID projectId, String night) {
        return client.target("%s/v1/internal/topics/projects/%s/run".formatted(baseUrl, projectId))
                .queryParam("workspace_id", workspaceId)
                .queryParam("night", night)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    /** The planner's window end: tomorrow (UTC). */
    private static LocalDate tomorrow() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1);
    }

    private static Instant tomorrowStart() {
        return tomorrow().atStartOfDay(ZoneOffset.UTC).toInstant();
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
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).checked(workspaceId, now);
            var dao = handle.attach(TopicProjectDAO.class);
            var drained = UUID.randomUUID();
            dao.startBackfills(List.of(TopicProjectDAO.Backfill.builder().workspaceId(workspaceId)
                    .projectId(drained).backfillStartedAt(now).backfillLastDoneAt(now).build()));
            dao.recordBackfills(List.of(TopicProjectDAO.Progress.builder().workspaceId(workspaceId)
                    .projectId(drained).drainedAt(now).build()));
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
}
