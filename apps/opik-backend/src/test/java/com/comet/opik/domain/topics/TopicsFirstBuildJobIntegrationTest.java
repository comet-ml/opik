package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.v1.jobs.TopicsFirstBuildJob;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
import org.quartz.JobKey;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The topics first-build job and the run by hand against real ClickHouse, MySQL and Redis, with opik-inference's
 * {@code /v1/generate} stubbed in WireMock. Tests seed {@code topic_vectors} with the fixture's traces moved to end at
 * tomorrow's start, the job's window end, and call {@link TopicsFirstBuildService#run(Instant)}; the scheduled job
 * fires at startup, before any workspace is opted in, and then hourly.
 */
@DisplayName("topics first-build job")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsFirstBuildJobIntegrationTest {

    private static final TopicsFixture FIXTURE = TopicsFixture.load();
    private static final int BUILT = 120;
    private static final int SMALL = 50;

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
    private TopicsFirstBuildService firstBuildService;
    private TopicsProjectRunner runner;
    private TopicsTestData data;
    private GuiceJobManager jobManager;
    private ClientSupport client;
    private String baseUrl;

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, GuiceJobManager jobManager,
            @Jit TopicsFirstBuildService firstBuildService, @Jit TopicsProjectRunner runner,
            @Jit TopicVectorsDAO vectorsDAO) {
        this.client = client;
        this.opikConfig = opikConfig;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.template = template;
        this.jobManager = jobManager;
        this.firstBuildService = firstBuildService;
        this.runner = runner;
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
    }

    @Test
    @DisplayName("enabled, the first-build job is scheduled on its interval")
    void jobIsScheduled() throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var jobKeys = new ArrayList<JobKey>();
        for (var key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            if (scheduler.getJobDetail(key).getJobClass() == TopicsFirstBuildJob.class) {
                jobKeys.add(key);
            }
        }

        assertThat(jobKeys).singleElement().satisfies(key -> assertThat(scheduler.getTriggersOfJob(key))
                .singleElement()
                .isInstanceOfSatisfying(SimpleTrigger.class,
                        trigger -> assertThat(trigger.getRepeatInterval()).isEqualTo(Duration.ofHours(1).toMillis())));
    }

    @Test
    @DisplayName("builds the opted-in project with minVectors in tomorrow's window, for tomorrow, its topics born "
            + "today; skips one with a list, one under minVectors, one with no trace written in the window, one of "
            + "another workspace and a deleted one; the next run builds nothing again")
    void firstBuild() {
        var workspaceId = enabledWorkspace();
        var built = UUID.randomUUID();
        var withList = UUID.randomUUID();
        var small = UUID.randomUUID();
        var quiet = UUID.randomUUID();
        var notOptedIn = UUID.randomUUID();
        var deleted = UUID.randomUUID();
        for (var projectId : List.of(built, withList, quiet, deleted)) {
            data.seed(workspaceId, projectId, 0, BUILT, tomorrowStart());
        }
        data.seed(workspaceId, small, BUILT, SMALL, tomorrowStart());
        data.seed(aiLabelsOnlyWorkspace(), notOptedIn, 0, BUILT, tomorrowStart());
        data.savedList(workspaceId, withList, tomorrowStart(), BUILT);
        template.inTransaction(WRITE, handle -> {
            // Its vectors are in the window, but its last trace was written before it.
            handle.execute("UPDATE projects SET last_updated_trace_at = ? WHERE id = ?",
                    tomorrowStart().minus(Duration.ofDays(31)), quiet.toString());
            handle.execute("DELETE FROM projects WHERE id = ?", deleted.toString());
            return null;
        });
        stubAnswer(FIXTURE.answer);

        assertThat(firstBuildService.candidates(tomorrow(), Instant.now()))
                .containsExactly(new TopicsFirstBuildService.Candidate(workspaceId, built, BUILT));

        runUntilBuilt(built);

        var project = data.topicProject(built);
        assertThat(project.runMode()).isEqualTo(TopicProject.RunMode.FIRST_BUILD);
        assertThat(project.savedRuns()).isEqualTo(1);
        assertThat(project.listRunId()).isEqualTo(project.runId());
        // The run is for tomorrow's window, but its topics are born today (UTC), never in the future.
        var topics = data.topics(built);
        assertThat(topics).isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.born()).isEqualTo(LocalDate.now(ZoneOffset.UTC)));
        assertThat(project.windowUntil()).isEqualTo(tomorrowStart());
        // The window is under maxVectors: the run labels it whole, and its pass finds nothing left.
        assertThat(project.labelsComplete()).isTrue();
        for (var skipped : List.of(small, quiet, notOptedIn, deleted)) {
            assertNoRun(skipped);
        }
        assertThat(data.topicProject(withList).savedRuns()).isEqualTo(1);

        firstBuildService.run(Instant.now());

        assertThat(data.topicProject(built)).satisfies(again -> {
            assertThat(again.savedRuns()).isEqualTo(1);
            assertThat(again.runId()).isEqualTo(project.runId());
        });
        assertThat(data.topics(built)).hasSameSizeAs(topics);
    }

    @Test
    @DisplayName("a run that outlasts projectTimeout fails with timeout and writes nothing; the next project still runs")
    void projectTimeout() {
        var workspaceId = enabledWorkspace();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        data.seed(workspaceId, first, 0, BUILT, tomorrowStart());
        data.seed(workspaceId, second, 0, BUILT, tomorrowStart());
        // The first Qwen call answers after the project timeout; every later call answers at once.
        stubAnswerInScenario(FIXTURE.answer, 4_000, Scenario.STARTED, "answered");
        stubAnswerInScenario(FIXTURE.answer, 0, "answered", "answered");
        var build = opikConfig.getTopics().getBuild();
        var projectTimeout = build.getProjectTimeout();
        build.setProjectTimeout(io.dropwizard.util.Duration.seconds(2));
        try {
            firstBuildService.run(Instant.now());
        } finally {
            build.setProjectTimeout(projectTimeout);
        }

        var timedOut = data.topicProject(first).runStatus() == TopicProject.RunStatus.FAILED ? first : second;
        var saved = timedOut.equals(first) ? second : first;
        assertThat(data.topicProject(saved).runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
        assertThat(data.topics(saved)).isNotEmpty();
        // The interrupted build may still get its slow answer: past it, a save that raced the timeout would show.
        Awaitility.await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(data.topicProject(timedOut)).satisfies(project -> {
                assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
                assertThat(project.runErrorCode()).isEqualTo(TopicProject.RunError.TIMEOUT);
                assertThat(project.failedRuns()).isEqualTo(1);
                assertThat(project.listRunId()).isNull();
            });
            assertThat(data.topics(timedOut)).isEmpty();
            assertThat(data.traceTopics(workspaceId, timedOut)).isEmpty();
        });
    }

    @Test
    @DisplayName("a failed first build is retried 1 h after it, and 6 h after a second failure, counting the failures "
            + "since the project's last saved run; a run by hand does not wait")
    void retriesAfterAFailure() {
        var workspaceId = enabledWorkspace();
        var failedRecently = UUID.randomUUID();
        var failedAnHourAgo = UUID.randomUUID();
        var failedTwiceRecently = UUID.randomUUID();
        // Its list was saved and then deleted by hand: the failures before the save do not count.
        var savedBetween = UUID.randomUUID();
        for (var projectId : List.of(failedRecently, failedAnHourAgo, failedTwiceRecently, savedBetween)) {
            data.seed(workspaceId, projectId, 0, BUILT, tomorrowStart());
        }
        insertFailedRun(workspaceId, failedRecently, Duration.ofMinutes(30));
        insertFailedRun(workspaceId, failedAnHourAgo, Duration.ofMinutes(70));
        insertFailedRun(workspaceId, failedTwiceRecently, Duration.ofHours(8));
        insertFailedRun(workspaceId, failedTwiceRecently, Duration.ofHours(5));
        for (var daysAgo : List.of(5, 4, 3)) {
            insertFailedRun(workspaceId, savedBetween, Duration.ofDays(daysAgo));
        }
        insertSavedRun(workspaceId, savedBetween, Instant.now().minus(Duration.ofDays(2)));
        insertFailedRun(workspaceId, savedBetween, Duration.ofMinutes(70));
        stubAnswer(FIXTURE.answer);

        assertThat(firstBuildService.candidates(tomorrow(), Instant.now()))
                .extracting(TopicsFirstBuildService.Candidate::projectId)
                .containsExactlyInAnyOrder(failedAnHourAgo, savedBetween);

        runUntilBuilt(failedAnHourAgo);
        runUntilBuilt(savedBetween);

        // A save resets the failures.
        for (var built : List.of(failedAnHourAgo, savedBetween)) {
            assertThat(data.topicProject(built)).satisfies(project -> {
                assertThat(project.failedRuns()).isZero();
                assertThat(project.firstFailedAt()).isNull();
            });
        }
        assertFailures(failedRecently, 1);
        assertFailures(failedTwiceRecently, 2);

        try (var response = rebuild(workspaceId, failedRecently, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                data.topicProject(failedRecently).runStatus()).isEqualTo(TopicProject.RunStatus.SAVED));
    }

    @Test
    @DisplayName("the internal endpoint starts a first build now, and refuses a project that has a list or a bad night")
    void rebuildByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        // The by-hand window ends at today's start by default.
        var today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        data.seed(workspaceId, projectId, 0, BUILT, today);
        stubAnswer(FIXTURE.answer);

        int runIdStatus;
        String runId;
        try (var response = rebuild(workspaceId, projectId)) {
            runIdStatus = response.getStatus();
            runId = response.readEntity(JsonNode.class).path("run_id").asText();
        }

        assertThat(runIdStatus).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.topicProject(projectId))
                .satisfies(project -> {
                    assertThat(project.runId()).hasToString(runId);
                    assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
                }));
        assertThat(data.topics(projectId)).isNotEmpty().allSatisfy(topic -> assertThat(topic.born())
                .isEqualTo(LocalDate.now(ZoneOffset.UTC)));
        try (var response = rebuild(workspaceId, projectId)) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = rebuild(workspaceId, UUID.randomUUID(), "yesterday")) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
        try (var response = rebuild(workspaceId, UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(2).toString())) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
        try (var response = rebuild(workspaceId, UUID.randomUUID(), "-999999999-01-05")) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("the internal endpoint builds the window of the night it is given")
    void rebuildByHandForANight() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(FIXTURE.answer);

        try (var response = rebuild(workspaceId, projectId, fixtureNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        awaitSaved(projectId);
        assertThat(data.topics(projectId)).isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.born()).isEqualTo(fixtureNight()));
    }

    @Test
    @DisplayName("a project whose vectors backfill has not drained is built neither by the job nor by hand (409), "
            + "while another project of the workspace is; ignore_backfill=true builds it by hand")
    void waitsForTheBackfill() {
        var workspaceId = enabledWorkspace();
        var waiting = UUID.randomUUID();
        var byHand = UUID.randomUUID();
        var drained = UUID.randomUUID();
        data.seed(workspaceId, waiting, 0, BUILT, tomorrowStart());
        data.seed(workspaceId, byHand, 0, BUILT, tomorrowStart());
        data.seed(workspaceId, drained, 0, BUILT, tomorrowStart());
        backfill(workspaceId, waiting, false);
        backfill(workspaceId, byHand, false);
        backfill(workspaceId, drained, true);
        stubAnswer(FIXTURE.answer);

        runUntilBuilt(drained);

        assertNoRun(waiting);
        assertNoRun(byHand);
        try (var response = rebuild(workspaceId, byHand, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("vectors backfill");
        }
        assertNoRun(byHand);
        try (var response = rebuild(workspaceId, byHand, tomorrow().toString(), true)) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        awaitSaved(byHand);
        assertNoRun(waiting);
    }

    @Test
    @DisplayName("by hand, a window under minVectors, another workspace's project, or a workspace opted in to "
            + "ai-labels but not to topics, is refused")
    void byHandFollowsTheJobsRules() {
        var workspaceId = enabledWorkspace();
        var small = UUID.randomUUID();
        data.seed(workspaceId, small, BUILT, SMALL);
        var foreign = UUID.randomUUID();
        data.seed(enabledWorkspace(), foreign, 0, BUILT);
        var notOptedInWorkspace = aiLabelsOnlyWorkspace();
        var notOptedIn = UUID.randomUUID();
        data.seed(notOptedInWorkspace, notOptedIn, 0, BUILT);

        try (var response = rebuild(workspaceId, small, fixtureNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = rebuild(workspaceId, foreign, fixtureNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = rebuild(notOptedInWorkspace, notOptedIn, fixtureNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        for (var refused : List.of(small, foreign, notOptedIn)) {
            assertNoRun(refused);
        }
    }

    @Test
    @DisplayName("a project whose run by hand holds its lock is left to that run: its attempt stays running")
    void liveRunIsLeftAlone() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT, tomorrowStart());
        var runId = insertRunningRun(workspaceId, projectId, Instant.now().minus(Duration.ofMinutes(2)));
        stubAnswer(FIXTURE.answer);
        assertThat(runner.lockProject(projectId)).isTrue();
        try {
            firstBuildService.run(Instant.now());
        } finally {
            runner.unlockProject(projectId);
        }

        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runId()).isEqualTo(runId);
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.RUNNING);
            assertThat(project.failedRuns()).isZero();
        });
        assertThat(data.topics(projectId)).isEmpty();
    }

    @Test
    @DisplayName("an attempt a dead pod left running past the lease is recorded as failed with abandoned when the "
            + "project's next run starts, and counts as a failure")
    void abandonedRun() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT, tomorrowStart());
        var startedAt = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
        insertRunningRun(workspaceId, projectId, startedAt);
        // The next run fails too, so the row still shows the abandoned attempt among its failures.
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(aResponse().withStatus(500)));

        firstBuildService.run(Instant.now());

        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.runErrorCode()).isEqualTo(TopicProject.RunError.LLM_ERROR);
            assertThat(project.failedRuns()).isEqualTo(2);
            assertThat(project.firstFailedAt()).isEqualTo(startedAt);
        });
    }

    @Test
    @DisplayName("a job run finishes the labelling pass of a saved list whose labels are not complete, marks them "
            + "complete; a project of a workspace not opted in or with AI labels off is "
            + "left as it is")
    void resumesAnUnfinishedPass() {
        var workspaceId = enabledWorkspace();
        var unfinished = UUID.randomUUID();
        // A list saved for the window whose pass was cut before it labelled a trace.
        data.seed(workspaceId, unfinished, 0, BUILT, tomorrowStart());
        data.savedList(workspaceId, unfinished, tomorrowStart(), BUILT);
        var notOptedIn = UUID.randomUUID();
        var notOptedInWorkspace = aiLabelsOnlyWorkspace();
        data.seed(notOptedInWorkspace, notOptedIn, 0, BUILT, tomorrowStart());
        data.savedList(notOptedInWorkspace, notOptedIn, tomorrowStart(), BUILT);
        var aiLabelsOff = UUID.randomUUID();
        var aiLabelsOffWorkspace = enabledWorkspace();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(aiLabelsOffWorkspace, false, "test");
            return null;
        });
        data.seed(aiLabelsOffWorkspace, aiLabelsOff, 0, BUILT, tomorrowStart());
        data.savedList(aiLabelsOffWorkspace, aiLabelsOff, tomorrowStart(), BUILT);
        var runId = data.topicProject(unfinished).listRunId();

        firstBuildService.run(Instant.now());

        assertThat(data.traceTopics(workspaceId, unfinished)).hasSize(BUILT)
                .allSatisfy(row -> assertThat(row.runId()).isEqualTo(runId));
        assertThat(data.topicProject(unfinished).labelsComplete()).isTrue();
        for (var left : List.of(notOptedIn, aiLabelsOff)) {
            assertThat(data.topicProject(left).labelsComplete()).isFalse();
        }
        assertThat(data.traceTopics(notOptedInWorkspace, notOptedIn)).isEmpty();
        assertThat(data.traceTopics(aiLabelsOffWorkspace, aiLabelsOff)).isEmpty();
    }

    @Test
    @DisplayName("a job run deletes the topics of a project that no longer exists, with their prototype vectors, and "
            + "keeps an existing project's")
    void deletedProjectsDataGoes() {
        var workspaceId = enabledWorkspace();
        var deleted = UUID.randomUUID();
        var kept = UUID.randomUUID();
        data.project(workspaceId, kept);
        data.savedList(workspaceId, deleted, tomorrowStart(), BUILT);
        data.savedList(workspaceId, kept, tomorrowStart(), BUILT);

        firstBuildService.run(Instant.now());

        assertThat(data.topics(deleted)).isEmpty();
        assertThat(data.topics(kept)).singleElement()
                .satisfies(topic -> assertThat(topic.vectors().vectors()).hasSize(1));
        assertThat(data.topicProject(kept).listRunId()).isNotNull();
    }

    /**
     * Runs the job's run until the project's last run is saved: a project whose lock a run by hand of an earlier test
     * still holds for a moment is skipped, and the next run takes it.
     */
    private void runUntilBuilt(UUID projectId) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            firstBuildService.run(Instant.now());
            assertSaved(projectId);
        });
    }

    private void awaitSaved(UUID projectId) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertSaved(projectId));
    }

    private void assertSaved(UUID projectId) {
        assertThat(data.topicProject(projectId)).isNotNull().satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.SAVED);
            assertThat(project.listRunId()).isEqualTo(project.runId());
        });
    }

    /** No run started for the project: it has no row, or only the vectors tick's backfill columns. */
    private void assertNoRun(UUID projectId) {
        var project = data.topicProject(projectId);
        assertThat(project == null ? null : project.runId()).isNull();
    }

    private void assertFailures(UUID projectId, int failures) {
        assertThat(data.topicProject(projectId)).satisfies(project -> {
            assertThat(project.runStatus()).isEqualTo(TopicProject.RunStatus.FAILED);
            assertThat(project.failedRuns()).isEqualTo(failures);
            assertThat(project.listRunId()).isNull();
        });
    }

    /** A failed first build of the project, started a minute before it finished {@code finishedAgo} ago. */
    private void insertFailedRun(String workspaceId, UUID projectId, Duration finishedAgo) {
        var finishedAt = Instant.now().minus(finishedAgo);
        var runId = startRun(workspaceId, projectId, finishedAt.minus(Duration.ofMinutes(1)));
        template.inTransaction(WRITE, handle -> handle.attach(TopicRunDAO.class).markFailed(workspaceId, projectId,
                runId, finishedAt, TopicProject.RunError.LLM_ERROR));
    }

    /** A first build of the project that saved, its list since deleted by hand: it resets the failures. */
    private void insertSavedRun(String workspaceId, UUID projectId, Instant startedAt) {
        var runId = startRun(workspaceId, projectId, startedAt);
        template.inTransaction(WRITE, handle -> handle.attach(TopicRunDAO.class).markSaved(workspaceId, projectId,
                runId, startedAt.plus(Duration.ofMinutes(1))));
    }

    private UUID insertRunningRun(String workspaceId, UUID projectId, Instant startedAt) {
        return startRun(workspaceId, projectId, startedAt);
    }

    /** Starts a first build on the project's row, and makes its project row: the job deletes the data of the rest. */
    private UUID startRun(String workspaceId, UUID projectId, Instant startedAt) {
        data.project(workspaceId, projectId);
        var runId = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).start(workspaceId, projectId, runId, TopicProject.RunMode.FIRST_BUILD,
                    startedAt);
            return null;
        });
        return runId;
    }

    /** The job's window end: tomorrow (UTC). */
    private static LocalDate tomorrow() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1);
    }

    private static Instant tomorrowStart() {
        return tomorrow().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The night after the fixture's last trace: its window is the fixture's. */
    private static LocalDate fixtureNight() {
        return LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);
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

    /**
     * The project's backfill as the vectors tick writes it on its {@code topic_projects} row, started now, and its
     * workspace checked now.
     */
    private void backfill(String workspaceId, UUID projectId, boolean drained) {
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicVectorBackfillDAO.class);
            dao.checked(workspaceId, now);
            dao.start(List.of(TopicVectorBackfillDAO.Backfill.builder().workspaceId(workspaceId)
                    .projectId(projectId).backfillStartedAt(now).backfillLastDoneAt(now).build()));
            if (drained) {
                dao.record(List.of(TopicVectorBackfillDAO.Progress.builder().workspaceId(workspaceId)
                        .projectId(projectId).drainedAt(now).build()));
            }
            return null;
        });
    }

    /**
     * Opted in to ai-labels only, not listed in topics.enabledWorkspaces: its vectors exist (written before topics were
     * turned off for it).
     */
    private String aiLabelsOnlyWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        return workspaceId;
    }

    private Response rebuild(String workspaceId, UUID projectId) {
        return rebuild(workspaceId, projectId, null);
    }

    private Response rebuild(String workspaceId, UUID projectId, String night) {
        return rebuild(workspaceId, projectId, night, false);
    }

    private Response rebuild(String workspaceId, UUID projectId, String night, boolean ignoreBackfill) {
        var target = client.target("%s/v1/internal/topics/projects/%s/run".formatted(baseUrl, projectId))
                .queryParam("workspace_id", workspaceId);
        if (ignoreBackfill) {
            target = target.queryParam("ignore_backfill", true);
        }
        return (night == null ? target : target.queryParam("night", night))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    private void stubAnswer(JsonNode output) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(output, 0)));
    }

    /** {@code output} after {@code delayMillis}, matched only in the scenario's {@code state}, which it then moves on. */
    private void stubAnswerInScenario(JsonNode output, int delayMillis, String state, String nextState) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).inScenario("answers").whenScenarioStateIs(state)
                .willSetStateTo(nextState).willReturn(TopicsTestData.answer(output, delayMillis)));
    }
}
