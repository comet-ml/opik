package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.assertj.core.api.ThrowingConsumer;
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

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                        new CustomConfig("topics.runs.abandonGrace", "5s"),
                        // The night pass has its own test (TopicsNightlyIntegrationTest), with its own night.
                        new CustomConfig("topics.nightly.enabled", "false")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TopicsRunPlanner planner;
    private TopicRunStore store;
    private TopicsTestData data;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicsRunPlanner planner,
            @Jit TopicVectorsDAO vectorsDAO, @Jit TopicRunStore store) {
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
        // Each test plans its own workspaces: the earlier ones must not take a slot or be eligible again.
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE");
            handle.execute("UPDATE topic_projects SET run_status = 'failed' "
                    + "WHERE run_status IN ('queued', 'running', 'cancelling')");
            return null;
        });
        opikConfig.getTopics().setEnabledWorkspaces("");
        opikConfig.getTopics().getRuns().setMaxInFlight(2);
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(FIXTURE.firstBuildAnswer,
                0)));
    }

    @Test
    @DisplayName("a plan queues the first build of the opted-in project with minVectors in tomorrow's window; a pod "
            + "runs it, then its labelling pass; a project with a list, one under minVectors, one with no trace in "
            + "the window, one of another workspace and a deleted one get no run; the next plan queues nothing")
    void plansAndRunsAFirstBuild() {
        var workspaceId = enabledWorkspace();
        var built = UUID.randomUUID();
        var withList = UUID.randomUUID();
        var small = UUID.randomUUID();
        var quiet = UUID.randomUUID();
        var notOptedIn = UUID.randomUUID();
        var deleted = UUID.randomUUID();
        for (var projectId : List.of(built, withList, quiet, deleted)) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        }
        data.seed(workspaceId, small, 0, SMALL, tomorrowStart());
        data.seed(aiLabelsOnlyWorkspace(), notOptedIn, 0, FIXTURE.size(), tomorrowStart());
        data.savedList(workspaceId, withList, tomorrowStart(), FIXTURE.size());
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE topic_projects SET labels_complete = TRUE WHERE project_id = ?",
                    withList.toString());
            // Its vectors are in the window, but its last trace was written before it.
            handle.execute("UPDATE projects SET last_updated_trace_at = ? WHERE id = ?",
                    tomorrowStart().minus(Duration.ofDays(31)), quiet.toString());
            handle.execute("DELETE FROM projects WHERE id = ?", deleted.toString());
            return null;
        });

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);

        var buildRun = awaitBuilt(built);
        // The saved list's labelling pass ran as its own run: nothing was left, so it marked the labels complete.
        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(data.topicProject(built)).satisfies(project -> {
            assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.LABELS);
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
            assertThat(project.listRunId()).isEqualTo(buildRun);
            assertThat(project.labelsComplete()).isTrue();
        }));
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
    @DisplayName("a failed first build waits 1 h before the next plan queues it again; a run by hand does not wait")
    void retriesAfterAFailure() {
        var workspaceId = enabledWorkspace();
        var failedRecently = UUID.randomUUID();
        var failedAnHourAgo = UUID.randomUUID();
        for (var projectId : List.of(failedRecently, failedAnHourAgo)) {
            data.seed(workspaceId, projectId, 0, FIXTURE.size(), tomorrowStart());
        }
        insertFailedRun(workspaceId, failedRecently, Duration.ofMinutes(30));
        insertFailedRun(workspaceId, failedAnHourAgo, Duration.ofMinutes(70));

        assertThat(planner.plan(Instant.now()).block()).isEqualTo(1);

        awaitBuilt(failedAnHourAgo);
        assertThat(data.topicProject(failedAnHourAgo)).satisfies(project -> {
            assertThat(project.failedRuns()).isZero();
            assertThat(project.firstFailedAt()).isNull();
        });
        assertThat(data.topicProject(failedRecently)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.failedRuns()).isEqualTo(1);
        });

        var runId = planner.runByHand(workspaceId, failedRecently, tomorrow(), false).block();

        assertThat(awaitBuilt(failedRecently)).isEqualTo(runId);
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
        var lostRun = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            TopicsTestData.startRun(dao, workspaceId, stale, UUID.randomUUID(), TopicProject.RunMode.FIRST_BUILD,
                    now.minus(Duration.ofMinutes(10)));
            TopicsTestData.startRun(dao, workspaceId, late, UUID.randomUUID(), TopicProject.RunMode.FIRST_BUILD,
                    now.minus(Duration.ofHours(2)));
            handle.execute("UPDATE topic_projects SET heartbeat_at = ? WHERE project_id = ?", now,
                    late.toString());
            dao.ensureRow(workspaceId, deleted);
            dao.queue(workspaceId, deleted, UUID.randomUUID(), TopicProject.RunMode.FIRST_BUILD, now);
            dao.ensureRow(workspaceId, lost);
            dao.queue(workspaceId, lost, lostRun, TopicProject.RunMode.FIRST_BUILD, now.minus(Duration.ofHours(1)));
            return null;
        });

        planner.plan(now).block();

        assertThat(data.topicProject(stale)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.runErrorCode()).isEqualTo(TopicProject.RunError.ABANDONED);
            assertThat(project.failedRuns()).isEqualTo(1);
        });
        assertThat(data.topicProject(late)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.CANCELLING);
            assertThat(project.cancelReason()).isEqualTo(TopicProject.CancelReason.DEADLINE);
        });
        assertThat(data.topicProject(deleted)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.runErrorCode()).isEqualTo(TopicProject.RunError.CANCELLED);
            assertThat(project.cancelReason()).isEqualTo(TopicProject.CancelReason.PROJECT_DELETED);
            assertThat(project.failedRuns()).isZero();
        });
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

    /** A failed first build of the project, started a minute before it finished {@code finishedAgo} ago. */
    private void insertFailedRun(String workspaceId, UUID projectId, Duration finishedAgo) {
        var finishedAt = Instant.now().minus(finishedAgo);
        var runId = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicProjectDAO.class);
            TopicsTestData.startRun(dao, workspaceId, projectId, runId, TopicProject.RunMode.FIRST_BUILD,
                    finishedAt.minus(Duration.ofMinutes(1)));
            return dao.markFailed(workspaceId, projectId, runId, finishedAt, TopicProject.RunError.LLM_ERROR);
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
     * so the first build of a project with no row of its own does not wait.
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
            dao.startBackfills(List.of(TopicProjectDAO.Backfill.builder().workspaceId(workspaceId)
                    .projectId(projectId).backfillStartedAt(now).backfillLastDoneAt(now).build()));
            if (drained) {
                dao.recordBackfills(List.of(TopicProjectDAO.Progress.builder().workspaceId(workspaceId)
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
            TopicsTestData.startRun(dao, workspaceId, saved, savedRun, TopicProject.RunMode.FIRST_BUILD, now);
            TopicsTestData.startRun(dao, workspaceId, cancelled, cancelledRun, TopicProject.RunMode.FIRST_BUILD, now);
            return dao.markSaved(workspaceId, saved, savedRun, now);
        });
        store.cancelProjects(workspaceId, List.of(cancelled), TopicProject.CancelReason.PROJECT_DELETED).block();

        assertThat(store.heartbeat(message(workspaceId, saved, savedRun)).block()).isTrue();
        assertThat(store.heartbeat(message(workspaceId, cancelled, cancelledRun)).block()).isFalse();
        assertThat(store.heartbeat(message(workspaceId, saved, TopicsTestData.newId())).block()).isFalse();
    }

    private static TopicsRunMessage message(String workspaceId, UUID projectId, UUID runId) {
        return TopicsRunMessage.builder().runId(runId).workspaceId(workspaceId).projectId(projectId)
                .mode(TopicProject.RunMode.FIRST_BUILD).windowTraces(0).build();
    }

    /** A {@link ConflictException} keeps its text in the response's {@link ErrorMessage}, not in getMessage(). */
    private static ThrowingConsumer<Throwable> conflict(String text) {
        return error -> assertThat(((ErrorMessage) ((ConflictException) error).getResponse().getEntity()).getMessage())
                .contains(text);
    }
}
