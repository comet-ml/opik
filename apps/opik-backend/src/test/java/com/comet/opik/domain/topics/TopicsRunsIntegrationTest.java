package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.ServiceUnavailableException;
import org.assertj.core.api.ThrowingConsumer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonReactiveClient;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Topics runs across pods as a black box: the planner ({@link TopicsRunPlanner}) queues runs on {@code topic_projects}
 * and publishes them on Redis, and the app's subscriber ({@link TopicsRunSubscriber}) claims and runs them, against
 * real MySQL, ClickHouse and Redis, with opik-inference's {@code /v1/generate} stubbed in WireMock. Tests seed
 * {@code topic_vectors} with the fixture's traces moved to end at tomorrow's start, the planner's window end.
 */
@DisplayName("topics runs across pods")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsRunsIntegrationTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int SMALL = 50;
    private static final Duration WAIT = Duration.ofSeconds(90);

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
                        new CustomConfig("topics.runs.abandonGrace", "5s")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TopicsRunPlanner planner;
    private TopicRunStore store;
    private TopicsTestData data;
    private ProjectResourceClient projects;

    private TopicsNightPass nightPass;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicsRunPlanner planner,
            @Jit TopicVectorsDAO vectorsDAO, @Jit TopicRunStore store, @Jit TopicsNightPass nightPass,
            ClientSupport client) {
        this.nightPass = nightPass;
        ClientSupportUtils.config(client);
        this.projects = new ProjectResourceClient(client, TestUtils.getBaseUrl(client),
                PodamFactoryUtils.newPodamFactory());
        this.opikConfig = opikConfig;
        this.store = store;
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
        // Each test plans its own workspaces: unlisting the earlier ones keeps them from being planned again.
        opikConfig.getTopics().setEnabledWorkspaces("");
        endRunsInFlight();
        opikConfig.getTopics().getRuns().setMaxInFlight(2);
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(FIXTURE.discoveryAnswer,
                0)));
    }

    @Test
    @DisplayName("a plan queues the discovery run of the opted-in project with minVectors in tomorrow's window; a pod "
            + "runs it, then its labelling pass; a project with a list, one under minVectors, one with no trace in "
            + "the window, one of another workspace and a deleted one get no run; the next plan queues nothing")
    void plansAndRunsADiscovery() {
        var workspaceId = enabledWorkspace();
        var built = UUID.randomUUID();
        var withList = UUID.randomUUID();
        var small = UUID.randomUUID();
        // Created through the API with no trace written: its vectors are in the window, its last trace time is not.
        var quiet = createProject(workspaceId);
        var notOptedIn = UUID.randomUUID();
        var deleted = UUID.randomUUID();
        for (var projectId : List.of(built, withList, deleted)) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        }
        data.seedVectors(workspaceId, quiet, 0, FIXTURE.size(), tomorrowStart());
        data.seed(workspaceId, small, 0, SMALL, tomorrowStart());
        data.seed(aiLabelsOnlyWorkspace(), notOptedIn, 0, FIXTURE.size(), tomorrowStart());
        data.savedList(workspaceId, withList, tomorrowStart(), FIXTURE.size());
        store.markLabelsComplete(workspaceId, withList, data.topicProject(withList).listRunId()).block();
        deleteProject(workspaceId, deleted);

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);

        var buildRun = awaitBuilt(built);
        // The saved list's labelling pass ran as its own run: nothing was left, so it marked the labels complete.
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(built).runMode())
                .isEqualTo(TopicProject.RunMode.LABELS));
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertProject(built, TopicProject.builder()
                .workspaceId(workspaceId).projectId(built).savedRuns(1).listRunId(buildRun).labelsComplete(true)
                .embedModel(TopicEmbedding.MODEL).runId(data.topicProject(built).runId())
                .runMode(TopicProject.RunMode.LABELS).runStatus(TopicProject.RunStatus.SAVED).build()));
        assertThat(data.topicProject(built).windowUntil()).isEqualTo(tomorrowStart());
        for (var skipped : List.of(small, quiet, notOptedIn, deleted)) {
            assertNoRun(skipped);
        }

        assertThat(planner.plan(Instant.now()).block()).isZero();
        assertThat(data.topicProject(built).savedRuns()).isEqualTo(1);
        assertThat(data.topicProject(withList).savedRuns()).isEqualTo(1);
    }

    @Test
    @DisplayName("a plan queues no more than topics.runs.maxInFlight runs across the cluster; a later plan queues the "
            + "rest once slots are free")
    void capsTheRunsInFlight() {
        var workspaceId = enabledWorkspace();
        var projects = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (var projectId : projects) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        }

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(2);

        Awaitility.await().atMost(WAIT.multipliedBy(2)).untilAsserted(() -> {
            planner.plan(Instant.now()).block();
            for (var projectId : projects) {
                var project = data.topicProject(projectId);
                assertThat(project).isNotNull();
                assertThat(project.listRunId()).isNotNull();
            }
        });
    }

    @Test
    @DisplayName("a failed discovery run waits 1 h before the next plan queues it again; a run by hand does not wait")
    void retriesAfterAFailure() {
        var workspaceId = enabledWorkspace();
        var failedRecently = UUID.randomUUID();
        var failedAnHourAgo = UUID.randomUUID();
        for (var projectId : List.of(failedRecently, failedAnHourAgo)) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        }
        var failedRecentlyRun = insertFailedRun(workspaceId, failedRecently, Duration.ofMinutes(30));
        insertFailedRun(workspaceId, failedAnHourAgo, Duration.ofMinutes(70));

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);

        awaitBuilt(failedAnHourAgo);
        assertThat(data.topicProject(failedAnHourAgo).failedRuns()).isZero();
        assertThat(data.topicProject(failedAnHourAgo).firstFailedAt()).isNull();
        assertProject(failedRecently, TopicProject.builder().workspaceId(workspaceId).projectId(failedRecently)
                .runId(failedRecentlyRun).runMode(TopicProject.RunMode.DISCOVERY)
                .runStatus(TopicProject.RunStatus.FAILED).runErrorCode(TopicProject.RunError.LLM_ERROR).failedRuns(1)
                .build());

        var runId = planner.runByHand(workspaceId, failedRecently, tomorrow(), false).block();

        assertThat(awaitBuilt(failedRecently)).isEqualTo(runId);
    }

    @Test
    @DisplayName("a run by hand whose publish fails after a pod claimed it and then stopped is reported as started, "
            + "and stays queued for another pod")
    void runByHandClaimedThenRequeuedDuringAFailedPublish(RedissonReactiveClient redisson,
            @Jit TopicWorkspaceService workspaceService, @Jit TopicsRunReconciler reconciler,
            @Jit TopicsDiscoveryCandidates candidates) {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        // The stream took the run and a pod claimed it, then that pod stopped, before the publish call failed.
        var planner = plannerWithFailingPublish(redisson, workspaceService, reconciler, candidates,
                message -> store.claim(message, Instant.now().plus(Duration.ofMinutes(30)))
                        .then(store.requeue(message)).then());

        var runId = planner.runByHand(workspaceId, projectId, tomorrow(), true).block();

        assertProject(projectId, TopicProject.builder().workspaceId(workspaceId).projectId(projectId).runId(runId)
                .runMode(TopicProject.RunMode.DISCOVERY).runStatus(TopicProject.RunStatus.QUEUED).build());
        assertThat(data.topicProject(projectId).runStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("a run by hand whose publish fails before any pod saw it is dropped as cancelled, not counted, and the "
            + "caller gets an error")
    void runByHandUnpublishedIsDropped(RedissonReactiveClient redisson, @Jit TopicWorkspaceService workspaceService,
            @Jit TopicsRunReconciler reconciler, @Jit TopicsDiscoveryCandidates candidates) {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        var planner = plannerWithFailingPublish(redisson, workspaceService, reconciler, candidates,
                message -> Mono.empty());

        assertThatThrownBy(() -> planner.runByHand(workspaceId, projectId, tomorrow(), true).block())
                .isInstanceOf(ServiceUnavailableException.class);

        assertProject(projectId, TopicProject.builder().workspaceId(workspaceId).projectId(projectId)
                .runId(data.topicProject(projectId).runId()).runMode(TopicProject.RunMode.DISCOVERY)
                .runStatus(TopicProject.RunStatus.FAILED).runErrorCode(TopicProject.RunError.CANCELLED).build());
    }

    @Test
    @DisplayName("a run by hand cancelled while queued, during its failed publish, gives the caller an error and stays "
            + "cancelled")
    void runByHandCancelledDuringAFailedPublish(RedissonReactiveClient redisson,
            @Jit TopicWorkspaceService workspaceService, @Jit TopicsRunReconciler reconciler,
            @Jit TopicsDiscoveryCandidates candidates) {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        // The planner of another pod dropped the queued run, its project deleted, before the publish call failed.
        var planner = plannerWithFailingPublish(redisson, workspaceService, reconciler, candidates,
                message -> store.dropQueued(message.workspaceId(), message.projectId(), message.runId(),
                        TopicProject.CancelReason.PROJECT_DELETED).then());

        assertThatThrownBy(() -> planner.runByHand(workspaceId, projectId, tomorrow(), true).block())
                .isInstanceOf(ServiceUnavailableException.class);

        assertProject(projectId, TopicProject.builder().workspaceId(workspaceId).projectId(projectId)
                .runId(data.topicProject(projectId).runId()).runMode(TopicProject.RunMode.DISCOVERY)
                .runStatus(TopicProject.RunStatus.FAILED).runErrorCode(TopicProject.RunError.CANCELLED)
                .cancelReason(TopicProject.CancelReason.PROJECT_DELETED).build());
    }

    /** The app's planner with a publisher that does {@code meanwhile}, then fails as a publish timeout does. */
    private TopicsRunPlanner plannerWithFailingPublish(RedissonReactiveClient redisson,
            TopicWorkspaceService workspaceService, TopicsRunReconciler reconciler,
            TopicsDiscoveryCandidates candidates, Function<TopicsRunMessage, Mono<Void>> meanwhile) {
        var config = opikConfig.getTopics();
        var publisher = new TopicsRunPublisher(redisson, config) {
            @Override
            Mono<Void> publish(TopicsRunMessage message) {
                return meanwhile.apply(message).then(Mono.error(new IllegalStateException("publish timed out")));
            }
        };
        return new TopicsRunPlanner(config, template, workspaceService, store, reconciler, candidates, publisher,
                new TopicsBackfillGate(config), nightPass);
    }

    @Test
    @DisplayName("a project whose vectors backfill has not drained is not planned and is refused by hand, while a "
            + "drained one is built; ignore_backfill builds it by hand; a project under minVectors, of another "
            + "workspace, or with a run in flight is refused")
    void backfillGateAndRunsByHand() {
        var workspaceId = enabledWorkspace();
        var waiting = UUID.randomUUID();
        var drained = UUID.randomUUID();
        var small = UUID.randomUUID();
        data.seed(workspaceId, waiting, 0, FIXTURE.size(), tomorrowStart());
        data.seed(workspaceId, drained, 0, FIXTURE.size(), tomorrowStart());
        data.seed(workspaceId, small, 0, SMALL, tomorrowStart());
        backfill(workspaceId, waiting, false);
        backfill(workspaceId, drained, true);

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);
        awaitBuilt(drained);
        assertNoRun(waiting);

        assertThatThrownBy(() -> planner.runByHand(workspaceId, waiting, tomorrow(), false).block())
                .isInstanceOf(ConflictException.class).satisfies(conflict("vectors backfill"));
        assertThatThrownBy(() -> planner.runByHand(workspaceId, small, tomorrow(), false).block())
                .isInstanceOf(ConflictException.class).satisfies(conflict("vectors in the window"));
        assertThatThrownBy(() -> planner.runByHand(enabledWorkspace(), waiting, tomorrow(), false).block())
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> planner.runByHand(aiLabelsOnlyWorkspace(), waiting, tomorrow(), false).block())
                .isInstanceOf(ConflictException.class).satisfies(conflict("not opted in"));
        assertNoRun(waiting);

        var runId = planner.runByHand(workspaceId, waiting, tomorrow(), true).block();
        assertThatThrownBy(() -> planner.runByHand(workspaceId, waiting, tomorrow(), true).block())
                .isInstanceOf(ConflictException.class);

        assertThat(awaitBuilt(waiting)).isEqualTo(runId);
    }

    @Test
    @DisplayName("a plan settles the runs in flight: a stale heartbeat is abandoned and counts, a run past its "
            + "deadline is cancelled, a queued run of a deleted project fails as cancelled and does not count, and a "
            + "run queued long ago is published again and runs")
    void settlesTheRunsInFlight() {
        var workspaceId = enabledWorkspace();
        var stale = UUID.randomUUID();
        var late = UUID.randomUUID();
        var deleted = UUID.randomUUID();
        var lost = UUID.randomUUID();
        for (var projectId : List.of(stale, late, lost)) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        }
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var staleRun = TopicsTestData.newId();
        var lateRun = TopicsTestData.newId();
        var deletedRun = TopicsTestData.newId();
        var lostRun = TopicsTestData.newId();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            TopicsTestData.startRun(dao, workspaceId, stale, staleRun, TopicProject.RunMode.DISCOVERY,
                    now.minus(Duration.ofMinutes(10)));
            TopicsTestData.startRun(dao, workspaceId, late, lateRun, TopicProject.RunMode.DISCOVERY,
                    now.minus(Duration.ofHours(2)));
            dao.heartbeat(workspaceId, late, lateRun, now);
            dao.ensureRow(workspaceId, deleted);
            dao.queue(workspaceId, deleted, deletedRun, TopicProject.RunMode.DISCOVERY, now);
            dao.ensureRow(workspaceId, lost);
            dao.queue(workspaceId, lost, lostRun, TopicProject.RunMode.DISCOVERY, now.minus(Duration.ofHours(1)));
            return null;
        });

        planner.plan(now).block();

        var project = TopicProject.builder().workspaceId(workspaceId).runMode(TopicProject.RunMode.DISCOVERY);
        assertProject(stale, project.projectId(stale).runId(staleRun).runStatus(TopicProject.RunStatus.FAILED)
                .runErrorCode(TopicProject.RunError.ABANDONED).failedRuns(1).build());
        assertProject(late, project.projectId(late).runId(lateRun).runStatus(TopicProject.RunStatus.CANCELLING)
                .runErrorCode(null).failedRuns(0).cancelReason(TopicProject.CancelReason.DEADLINE).build());
        // A delete whose topic_projects cleanup failed: the planner still drops its queued run, not counted.
        assertProject(deleted, project.projectId(deleted).runId(deletedRun).runStatus(TopicProject.RunStatus.FAILED)
                .runErrorCode(TopicProject.RunError.CANCELLED).failedRuns(0)
                .cancelReason(TopicProject.CancelReason.PROJECT_DELETED).build());
        assertThat(awaitBuilt(lost)).isEqualTo(lostRun);
    }

    @Test
    @DisplayName("a plan queues the labelling pass of a saved list whose labels are not complete; a pod finishes it "
            + "and marks the labels complete; a workspace not opted in is left as it is")
    void resumesAnUnfinishedPass() {
        var workspaceId = enabledWorkspace();
        var unfinished = UUID.randomUUID();
        data.seed(workspaceId, unfinished, 0, FIXTURE.size(), tomorrowStart());
        data.savedList(workspaceId, unfinished, tomorrowStart(), FIXTURE.size());
        var notOptedInWorkspace = aiLabelsOnlyWorkspace();
        var notOptedIn = UUID.randomUUID();
        data.seed(notOptedInWorkspace, notOptedIn, 0, FIXTURE.size(), tomorrowStart());
        data.savedList(notOptedInWorkspace, notOptedIn, tomorrowStart(), FIXTURE.size());
        var runId = data.topicProject(unfinished).listRunId();

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(unfinished).labelsComplete())
                .isTrue());
        assertThat(data.traceTopics(workspaceId, unfinished)).hasSize(FIXTURE.size())
                .allSatisfy(row -> assertThat(row.runId()).isEqualTo(runId));
        assertThat(data.topicProject(unfinished).runMode()).isEqualTo(TopicProject.RunMode.LABELS);
        assertThat(data.topicProject(notOptedIn).labelsComplete()).isFalse();
        assertThat(data.traceTopics(notOptedInWorkspace, notOptedIn)).isEmpty();
    }

    @Test
    @DisplayName("a queued nightly update of a saved list runs on a pod: it saves the list under its own run with the "
            + "topics' ids, writes one trace_topics row per trace as its run's, and its labelling pass runs after it")
    void runsANightlyUpdate(@Jit TopicsRunPublisher publisher) {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        // A list of the night before the update's: a pod skips an update whose night the list already covers.
        planner.runByHand(workspaceId, projectId, tomorrow().minusDays(1), false).block();
        awaitBuilt(projectId);
        awaitLabelsComplete(projectId);
        var before = data.topics(projectId);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(emptyNaming(), 0)));

        var updateRunId = queueUpdate(publisher, workspaceId, projectId);

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId).listRunId())
                .isEqualTo(updateRunId));
        awaitLabelsComplete(projectId);
        var project = data.topicProject(projectId);
        assertThat(project.savedRuns()).isEqualTo(2);
        assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
        assertThat(data.topics(projectId)).isNotEmpty().extracting(Topic::id)
                .isSubsetOf(before.stream().map(Topic::id).toList());
        assertThat(data.traceTopics(workspaceId, projectId)).hasSize(FIXTURE.size())
                .allSatisfy(row -> assertThat(row.runId()).isEqualTo(updateRunId));
    }

    @Test
    @DisplayName("a queued nightly update that leaves no live topic deletes every topic, clears the list, keeps the "
            + "traces' last rows and queues no labelling pass; the next plan queues a discovery run")
    void nightlyUpdateClearsAnEmptyList(@Jit TopicsRunPublisher publisher) {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        // One topic whose only vector is far from every trace: no trace tonight, so it retires.
        // A list of the night before the update's: a pod skips an update whose night the list already covers.
        data.savedList(workspaceId, projectId, tomorrowStart().minus(Duration.ofDays(1)), FIXTURE.size());
        store.markLabelsComplete(workspaceId, projectId, data.topicProject(projectId).listRunId()).block();
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(emptyNaming(), 0)));

        var updateRunId = queueUpdate(publisher, workspaceId, projectId);

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId).runStatus())
                .isEqualTo(TopicProject.RunStatus.SAVED));
        var project = data.topicProject(projectId);
        assertThat(project.runId()).isEqualTo(updateRunId);
        assertThat(project.listRunId()).isNull();
        assertThat(project.savedRuns()).isEqualTo(2);
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.traceTopics(workspaceId, projectId)).isEmpty();

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);
        assertThat(data.topicProject(projectId).runMode()).isEqualTo(TopicProject.RunMode.DISCOVERY);
    }

    /** Queues and publishes the project's nightly update for tomorrow's window, as the nightly job will. */
    private UUID queueUpdate(TopicsRunPublisher publisher, String workspaceId, UUID projectId) {
        var runId = store.queue(workspaceId, projectId, TopicProject.RunMode.UPDATE).block();
        publisher.publish(TopicsRunMessage.builder().runId(runId).workspaceId(workspaceId).projectId(projectId)
                .mode(TopicProject.RunMode.UPDATE).night(tomorrow()).windowTraces(FIXTURE.size()).build()).block();
        return runId;
    }

    private void awaitLabelsComplete(UUID projectId) {
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(projectId).labelsComplete())
                .isTrue());
    }

    /** A naming answer that changes nothing. */
    private static JsonNode emptyNaming() {
        var naming = InferenceJson.MAPPER.createObjectNode();
        naming.putArray("assign");
        naming.putArray("new");
        naming.putArray("other");
        return naming;
    }

    /** Waits until the project's discovery run is saved and returns its run id. */
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

    /** A failed discovery run of the project, started a minute before it finished {@code finishedAgo} ago. */
    private UUID insertFailedRun(String workspaceId, UUID projectId, Duration finishedAgo) {
        var finishedAt = Instant.now().minus(finishedAgo);
        var runId = TopicsTestData.newId();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            TopicsTestData.startRun(dao, workspaceId, projectId, runId, TopicProject.RunMode.DISCOVERY,
                    finishedAt.minus(Duration.ofMinutes(1)));
            return dao.markFailed(workspaceId, projectId, runId, finishedAt, TopicProject.RunError.LLM_ERROR);
        });
        return runId;
    }

    /** The project's row as {@code expected}, but for its timestamps and its Other vectors. */
    private void assertProject(UUID projectId, TopicProject expected) {
        assertThat(data.topicProject(projectId)).usingRecursiveComparison()
                .ignoringFieldsOfTypes(Instant.class, PrototypeVectors.class).isEqualTo(expected);
    }

    /** Creates a project as a user does, through the projects API, and returns its id. */
    private UUID createProject(String workspaceId) {
        var apiKey = TopicsTestData.newId().toString();
        var workspaceName = "topics-" + workspaceId;
        AuthTestUtils.mockTargetWorkspace(wireMock, apiKey, workspaceName, workspaceId, "user");
        return projects.createProject("topics-" + TopicsTestData.newId(), apiKey, workspaceName);
    }

    /** Deletes the project as a user does, through the projects API; its topic_projects row goes with it. */
    private void deleteProject(String workspaceId, UUID projectId) {
        var apiKey = UUID.randomUUID().toString();
        var workspaceName = "topics-" + workspaceId;
        AuthTestUtils.mockTargetWorkspace(wireMock, apiKey, workspaceName, workspaceId, "user");
        projects.deleteProject(projectId, apiKey, workspaceName);
    }

    /**
     * Ends every run an earlier test left in flight, so none takes a slot of {@code topics.runs.maxInFlight}: with no
     * workspace opted in, each plan cancels them as the planner does for an opt-out, and the pods or the planner end
     * the cancelled ones.
     */
    private void endRunsInFlight() {
        Awaitility.await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(() -> {
            planner.plan(Instant.now()).block();
            return store.findInFlight().block().isEmpty();
        });
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
        backfill(workspaceId, UUID.randomUUID(), true);
        return workspaceId;
    }

    /** The project's backfill as the vectors tick writes it, started now, and its workspace checked now. */
    private void backfill(String workspaceId, UUID projectId, boolean drained) {
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).checked(workspaceId, now);
            var dao = handle.attach(TopicProjectDAO.class);
            dao.startBackfills(List.of(TopicBackfill.builder().workspaceId(workspaceId)
                    .projectId(projectId).backfillStartedAt(now).backfillLastDoneAt(now).build()));
            if (drained) {
                dao.recordBackfills(List.of(TopicBackfillProgress.builder().workspaceId(workspaceId)
                        .projectId(projectId).drainedAt(now).build()));
            }
            return null;
        });
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

    @Test
    @DisplayName("a heartbeat keeps a run going after the run saved its row, so the trace_topics insert and the "
            + "labels run after the save are not cut off; a cancelled run's heartbeat stops it")
    void heartbeatAfterTheSave() {
        var workspaceId = enabledWorkspace();
        var saved = UUID.randomUUID();
        var cancelled = UUID.randomUUID();
        var savedRun = TopicsTestData.newId();
        var cancelledRun = TopicsTestData.newId();
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            TopicsTestData.startRun(dao, workspaceId, saved, savedRun, TopicProject.RunMode.DISCOVERY, now);
            TopicsTestData.startRun(dao, workspaceId, cancelled, cancelledRun, TopicProject.RunMode.DISCOVERY, now);
            dao.cancel(workspaceId, cancelled, cancelledRun, TopicProject.CancelReason.PROJECT_DELETED);
            return dao.markSaved(workspaceId, saved, savedRun, now);
        });

        assertThat(store.heartbeat(message(workspaceId, saved, savedRun)).block()).isTrue();
        assertThat(store.heartbeat(message(workspaceId, cancelled, cancelledRun)).block()).isFalse();
        assertThat(store.heartbeat(message(workspaceId, saved, TopicsTestData.newId())).block()).isFalse();
    }

    @Test
    @DisplayName("a pod that stops mid-run puts the run back to queued, with its run id, and counts no failure; it "
            + "publishes the run again, so another pod runs it without waiting for republishAfter")
    void stopMidRunRequeues(RedissonReactiveClient redisson, @Jit TopicsRunService runService,
            @Jit TopicWindowLabelService labelService) {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        // The model answers after the stop: the run is mid-call when its pod stops.
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(FIXTURE.discoveryAnswer,
                60_000)));
        // A pod of its own, on a stream of its own, so the app's subscriber does not take the run.
        var runs = spy(opikConfig.getTopics().getRuns());
        doReturn("topics-runs-stop-" + UUID.randomUUID()).when(runs).getStreamName();
        var config = spy(opikConfig.getTopics());
        doReturn(runs).when(config).getRuns();
        var publisher = new TopicsRunPublisher(redisson, config);
        var pod = new TopicsRunSubscriber(config, redisson, store, runService, labelService, planner, publisher);
        var other = new TopicsRunSubscriber(config, redisson, store, runService, labelService, planner, publisher);
        pod.start();
        try {
            var runId = store.queue(workspaceId, projectId, TopicProject.RunMode.DISCOVERY).block();
            publisher.publish(TopicsRunMessage.builder().runId(runId)
                    .workspaceId(workspaceId).projectId(projectId).mode(TopicProject.RunMode.DISCOVERY)
                    .night(tomorrow()).windowTraces(FIXTURE.size()).build()).block();
            Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(wireMock.findAll(
                    postRequestedFor(urlEqualTo(GENERATE_PATH)))).isNotEmpty());
            assertThat(data.topicProject(projectId).runStatus()).isEqualTo(TopicProject.RunStatus.RUNNING);

            pod.stop();

            assertProject(projectId, TopicProject.builder().workspaceId(workspaceId).projectId(projectId)
                    .runId(runId).runMode(TopicProject.RunMode.DISCOVERY).runStatus(TopicProject.RunStatus.QUEUED)
                    .build());

            // The pod that starts next takes the message published at the stop, well before republishAfter.
            wireMock.resetAll();
            wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(
                    FIXTURE.discoveryAnswer, 0)));
            other.start();
            assertThat(awaitBuilt(projectId)).isEqualTo(runId);
        } finally {
            pod.stop();
            other.stop();
        }
    }

    private static TopicsRunMessage message(String workspaceId, UUID projectId, UUID runId) {
        return TopicsRunMessage.builder().runId(runId).workspaceId(workspaceId).projectId(projectId)
                .mode(TopicProject.RunMode.DISCOVERY).windowTraces(0).build();
    }

    /** A {@link ConflictException} keeps its text in the response's {@link ErrorMessage}, not in getMessage(). */
    private static ThrowingConsumer<Throwable> conflict(String text) {
        return error -> assertThat(((ErrorMessage) ((ConflictException) error).getResponse().getEntity()).getMessage())
                .contains(text);
    }
}
