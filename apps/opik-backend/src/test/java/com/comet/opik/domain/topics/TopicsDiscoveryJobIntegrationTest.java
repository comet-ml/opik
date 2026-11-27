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
import com.comet.opik.api.resources.v1.events.TopicProjectsDeletedListener;
import com.comet.opik.api.resources.v1.jobs.TopicsDiscoveryJob;
import com.comet.opik.api.resources.v1.jobs.TopicsSweepJob;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The discovery job, the run by hand and the project delete against real ClickHouse, MySQL and Redis, with
 * opik-inference's {@code /v1/generate} stubbed in WireMock. The planner and the runs themselves are
 * {@link TopicsRunsIntegrationTest}'s. The scheduled job fires at startup, before any workspace is opted in, which
 * holds its plan and sweep locks; a test that fires the job frees the lock it needs first.
 */
@DisplayName("topics discovery job")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsDiscoveryJobIntegrationTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final Lock PLAN_LOCK = new Lock("topics:discovery:plan");

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
                        new CustomConfig("topics.runs.planInterval", "1h"),
                        new CustomConfig("topics.runs.poolingInterval", "200ms"),
                        new CustomConfig("topics.runs.longPollingDuration", "200ms")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TopicsTestData data;
    private GuiceJobManager jobManager;
    private TopicsDiscoveryJob job;
    private TopicsSweepJob sweepJob;
    private TopicsRunPlanner planner;
    private LockService lockService;
    private TopicProjectsDeletedListener projectDeletedListener;
    private ClientSupport client;
    private String baseUrl;

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, GuiceJobManager jobManager, LockService lockService,
            @Jit TopicsDiscoveryJob job, @Jit TopicsSweepJob sweepJob, @Jit TopicsRunPlanner planner,
            @Jit TopicProjectsDeletedListener projectDeletedListener,
            @Jit TopicVectorsDAO vectorsDAO) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.opikConfig = opikConfig;
        this.template = template;
        this.jobManager = jobManager;
        this.job = job;
        this.sweepJob = sweepJob;
        this.planner = planner;
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
        // Each test opts in its own workspaces. Topics takes a workspace only while it is listed here too, so clearing
        // the list keeps the earlier tests' workspaces from being planned again.
        opikConfig.getTopics().setEnabledWorkspaces("");
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(FIXTURE.discoveryAnswer,
                0)));
    }

    @Test
    @DisplayName("with topics on, the discovery job is scheduled on topics.runs.planInterval and the sweep job on "
            + "topics.storage.sweepInterval")
    void jobsAreScheduled() throws SchedulerException {
        assertThat(triggers(TopicsDiscoveryJob.class)).singleElement().isInstanceOfSatisfying(SimpleTrigger.class,
                trigger -> assertThat(trigger.getRepeatInterval()).isEqualTo(Duration.ofHours(1).toMillis()));
        assertThat(triggers(TopicsSweepJob.class)).singleElement().isInstanceOfSatisfying(SimpleTrigger.class,
                trigger -> assertThat(trigger.getRepeatInterval()).isEqualTo(Duration.ofHours(24).toMillis()));
    }

    @Test
    @DisplayName("an interrupted discovery job plans nothing, even when a plan is due")
    void interruptedJobPlansNothing() throws Exception {
        var workspaceId = enabledWorkspace();
        var projectId = TopicsTestData.newId();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        lockService.unlockUsingToken(PLAN_LOCK).block();
        var stopped = new TopicsDiscoveryJob(opikConfig.getTopics(), planner, lockService);

        stopped.interrupt();
        stopped.doJob(null);

        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertNoRun(projectId));
    }

    @Test
    @DisplayName("a job fire plans: the opted-in project's discovery run is queued and run; another fire within the "
            + "interval plans nothing")
    void jobPlans() {
        var workspaceId = enabledWorkspace();
        var projectId = TopicsTestData.newId();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        lockService.unlockUsingToken(PLAN_LOCK).block();

        // A fire can skip while the startup fire still holds the leader lock: fire until one plans.
        Awaitility.await().atMost(WAIT).untilAsserted(() -> {
            job.doJob(null);
            assertThat(data.topicProject(projectId)).isNotNull()
                    .satisfies(project -> assertThat(project.runId()).isNotNull());
        });
        var savedRun = awaitSaved(projectId);

        var other = TopicsTestData.newId();
        data.seed(workspaceId, other, 0, FIXTURE.size(), tomorrowStart());
        job.doJob(null);
        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertNoRun(other));
        assertThat(data.topicProject(projectId).listRunId()).isEqualTo(savedRun);
    }

    @Test
    @DisplayName("a sweep job fire deletes the topics of projects that no longer exist, with their prototype vectors, in "
            + "batches, and keeps an existing project's")
    void jobSweepsDeletedProjects() {
        var workspaceId = enabledWorkspace();
        // More than topics.storage.deleteBatchSize (2 in the tests), so the sweep deletes in two transactions.
        var deleted = List.of(TopicsTestData.newId(), TopicsTestData.newId(), TopicsTestData.newId());
        var kept = TopicsTestData.newId();
        data.project(workspaceId, kept);
        deleted.forEach(projectId -> data.savedList(workspaceId, projectId, tomorrowStart(), FIXTURE.size()));
        data.savedList(workspaceId, kept, tomorrowStart(), FIXTURE.size());
        // A fire can skip while the startup fire still holds the lock: fire until one sweeps.
        Awaitility.await().atMost(WAIT).untilAsserted(() -> {
            sweepJob.doJob(null);
            assertThat(deleted).allSatisfy(projectId -> assertThat(data.topics(projectId)).isEmpty());
        });
        assertThat(data.topics(kept)).singleElement()
                .satisfies(topic -> assertThat(topic.vectors().vectors()).hasSize(1));
    }

    @Test
    @DisplayName("the internal endpoint queues a discovery run and returns its run id, which a pod runs; a second "
            + "call is refused (409) with the planner's reason, and a bad night is refused (400)")
    void runByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = TopicsTestData.newId();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());

        String runId;
        try (var response = runProject(workspaceId, projectId, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
            runId = response.readEntity(JsonNode.class).path("run_id").asText();
        }

        assertThat(awaitSaved(projectId)).hasToString(runId);
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
            try (var response = runProject(workspaceId, TopicsTestData.newId(), night)) {
                assertThat(response.getStatus()).isEqualTo(400);
            }
        }
    }

    @Test
    @DisplayName("the internal reset forgets a saved list and deletes its topics, so a new discovery run builds one; with "
            + "a run in flight it is refused (409) and changes nothing")
    void resetByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = TopicsTestData.newId();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        try (var response = runProject(workspaceId, projectId, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        var firstRun = awaitSaved(projectId);
        assertThat(data.topics(projectId)).isNotEmpty();

        try (var response = resetProject(workspaceId, projectId)) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.topicProject(projectId).listRunId()).isNull();
        try (var response = runProject(workspaceId, projectId, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        assertThat(awaitSaved(projectId)).isNotEqualTo(firstRun);

        var inFlight = TopicsTestData.newId();
        data.savedList(workspaceId, inFlight, tomorrowStart(), FIXTURE.size());
        template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class).queue(workspaceId, inFlight,
                TopicsTestData.newId(), TopicProject.RunMode.UPDATE, Instant.now()));
        var listRun = data.topicProject(inFlight).listRunId();
        try (var response = resetProject(workspaceId, inFlight)) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("a run in flight");
        }
        assertThat(data.topicProject(inFlight).listRunId()).isEqualTo(listRun);
        assertThat(data.topics(inFlight)).hasSize(1);
        try (var response = resetProject(aiLabelsOnlyWorkspace(), inFlight)) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("not opted in");
        }
    }

    @Test
    @DisplayName("the internal workspace reset forgets every saved list of the workspace and deletes its topics, skips "
            + "and lists the projects with a run in flight, and keeps another workspace's lists; a workspace not opted "
            + "in is refused (409)")
    void resetWorkspaceByHand() {
        var workspaceId = enabledWorkspace();
        var first = TopicsTestData.newId();
        var second = TopicsTestData.newId();
        var inFlight = TopicsTestData.newId();
        for (var projectId : List.of(first, second, inFlight)) {
            data.savedList(workspaceId, projectId, tomorrowStart(), FIXTURE.size());
        }
        template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class).queue(workspaceId, inFlight,
                TopicsTestData.newId(), TopicProject.RunMode.UPDATE, Instant.now()));
        var otherWorkspace = enabledWorkspace();
        var otherProject = TopicsTestData.newId();
        data.savedList(otherWorkspace, otherProject, tomorrowStart(), FIXTURE.size());

        try (var response = resetWorkspace(workspaceId)) {
            assertThat(response.getStatus()).isEqualTo(200);
            var body = response.readEntity(JsonNode.class);
            assertThat(body.path("reset").asInt()).isEqualTo(2);
            assertThat(body.path("skipped_in_flight").asInt()).isEqualTo(1);
            assertThat(body.path("in_flight_project_ids")).singleElement()
                    .satisfies(id -> assertThat(id.asText()).isEqualTo(inFlight.toString()));
        }
        for (var projectId : List.of(first, second)) {
            assertThat(data.topics(projectId)).isEmpty();
            assertThat(data.topicProject(projectId).listRunId()).isNull();
        }
        for (var projectId : List.of(inFlight, otherProject)) {
            assertThat(data.topics(projectId)).hasSize(1);
            assertThat(data.topicProject(projectId).listRunId()).isNotNull();
        }

        try (var response = resetWorkspace(aiLabelsOnlyWorkspace())) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("not opted in");
        }
    }

    @Test
    @DisplayName("a project whose row a delete removes while the workspace reset runs is neither reset nor reported in "
            + "flight: the reset waits for the delete")
    void resetWorkspaceWaitsForADelete() throws Exception {
        var workspaceId = enabledWorkspace();
        var deleted = TopicsTestData.newId();
        data.savedList(workspaceId, deleted, tomorrowStart(), FIXTURE.size());
        var deleting = new CountDownLatch(1);
        // Deletes the row and holds its lock for a while before the commit, as a project delete can.
        var delete = CompletableFuture.runAsync(() -> template.inTransaction(WRITE, handle -> {
            handle.attach(TopicProjectDAO.class).deleteByProjects(workspaceId, List.of(deleted));
            deleting.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(2));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(deleting.await(30, TimeUnit.SECONDS)).isTrue();

        try (var response = resetWorkspace(workspaceId)) {
            assertThat(response.getStatus()).isEqualTo(200);
            var body = response.readEntity(JsonNode.class);
            assertThat(body.path("reset").asInt()).isZero();
            assertThat(body.path("skipped_in_flight").asInt()).isZero();
            assertThat(body.path("in_flight_project_ids")).isEmpty();
        }
        delete.get(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("deleting projects deletes their topics, with their prototype vectors, in batches, and keeps another "
            + "project's")
    void projectDeleteDeletesItsTopics() {
        var workspaceId = enabledWorkspace();
        // More than topics.storage.deleteBatchSize (2 in the tests), so the listener deletes in two transactions.
        var deleted = List.of(TopicsTestData.newId(), TopicsTestData.newId(), TopicsTestData.newId());
        var kept = TopicsTestData.newId();
        deleted.forEach(projectId -> data.savedList(workspaceId, projectId, tomorrowStart(), FIXTURE.size()));
        data.savedList(workspaceId, kept, tomorrowStart(), FIXTURE.size());

        projectDeletedListener.onProjectsDeleted(new ProjectsDeleted(Set.copyOf(deleted), workspaceId, "test"));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(deleted)
                .allSatisfy(projectId -> assertThat(data.topics(projectId)).isEmpty()));
        assertThat(data.topics(kept)).singleElement()
                .satisfies(topic -> assertThat(topic.vectors().vectors()).hasSize(1));
    }

    @Test
    @DisplayName("a workspace listed at a stage before build gets no run from a job fire and is refused by hand (409); "
            + "one listed at build gets its run")
    void stageBeforeBuildIsSkipped() {
        var vectorsOnly = aiLabelsOnlyWorkspace();
        var atBuild = aiLabelsOnlyWorkspace();
        opikConfig.getTopics().setEnabledWorkspaces(vectorsOnly + ":vectors," + atBuild + ":build");
        var skipped = TopicsTestData.newId();
        var planned = TopicsTestData.newId();
        data.seed(vectorsOnly, skipped, 0, FIXTURE.size(), tomorrowStart());
        data.seed(atBuild, planned, 0, FIXTURE.size(), tomorrowStart());
        drainedBackfill(vectorsOnly);
        drainedBackfill(atBuild);
        lockService.unlockUsingToken(PLAN_LOCK).block();

        Awaitility.await().atMost(WAIT).untilAsserted(() -> {
            job.doJob(null);
            assertThat(data.topicProject(planned)).isNotNull()
                    .satisfies(project -> assertThat(project.runId()).isNotNull());
        });
        awaitSaved(planned);
        assertNoRun(skipped);
        try (var response = runProject(vectorsOnly, skipped, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("not opted in");
        }
    }

    private Class<?> jobClass(org.quartz.JobKey key) {
        try {
            return jobManager.getScheduler().getJobDetail(key).getJobClass();
        } catch (SchedulerException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<? extends org.quartz.Trigger> triggers(Class<?> jobClass) throws SchedulerException {
        return jobManager.getScheduler().getJobKeys(GroupMatcher.anyGroup()).stream()
                .filter(key -> jobClass(key) == jobClass)
                .flatMap(key -> triggers(key).stream())
                .toList();
    }

    private List<? extends org.quartz.Trigger> triggers(org.quartz.JobKey key) {
        try {
            return jobManager.getScheduler().getTriggersOfJob(key);
        } catch (SchedulerException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Waits until the project's discovery run is saved and returns its run id. */
    private UUID awaitSaved(UUID projectId) {
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

    private Response resetProject(String workspaceId, UUID projectId) {
        return client.target("%s/v1/internal/topics/projects/%s/reset".formatted(baseUrl, projectId))
                .queryParam("workspace_id", workspaceId)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    private Response resetWorkspace(String workspaceId) {
        return client.target("%s/v1/internal/topics/reset".formatted(baseUrl))
                .queryParam("workspace_id", workspaceId)
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
     * so the discovery run of a project with no row of its own does not wait.
     */
    private String enabledWorkspace() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var topics = opikConfig.getTopics();
        var listed = new TreeSet<>(topics.enabledWorkspaceIds());
        listed.add(workspaceId);
        topics.setEnabledWorkspaces(String.join(",", listed));
        drainedBackfill(workspaceId);
        return workspaceId;
    }

    /** One drained backfill row, of another project, so the workspace's vectors tick has no backlog. */
    private void drainedBackfill(String workspaceId) {
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).checked(workspaceId, now);
            var dao = handle.attach(TopicProjectDAO.class);
            var drained = TopicsTestData.newId();
            dao.startBackfills(List.of(TopicBackfill.builder().workspaceId(workspaceId)
                    .projectId(drained).backfillStartedAt(now).backfillLastDoneAt(now).build()));
            dao.recordBackfills(List.of(TopicBackfillProgress.builder().workspaceId(workspaceId)
                    .projectId(drained).drainedAt(now).build()));
            return null;
        });
    }

    /** Opted in to ai-labels only, not listed in topics.enabledWorkspaces. */
    private String aiLabelsOnlyWorkspace() {
        var workspaceId = TopicsTestData.newId().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        return workspaceId;
    }
}
