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

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.domain.topics.TopicsTestData.json;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
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
        try {
            PostCutoverTraces.migrate(clickHouse);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }

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
    private TopicsTestData data;
    private GuiceJobManager jobManager;
    private ClientSupport client;
    private String baseUrl;

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, GuiceJobManager jobManager,
            @Jit TopicsFirstBuildService firstBuildService, @Jit TopicVectorsDAO vectorsDAO) {
        this.client = client;
        this.opikConfig = opikConfig;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.template = template;
        this.jobManager = jobManager;
        this.firstBuildService = firstBuildService;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, new TopicsTestData.Traces(
                FIXTURE.traceIds, FIXTURE.createdAt, FIXTURE.intents, FIXTURE.vectors, FIXTURE.night));
    }

    @AfterAll
    void afterAll() {
        wireMock.stop();
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
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertState(TopicProjectState.builder()
                    .projectId(withList)
                    .workspaceId(workspaceId)
                    .rebuilds(1)
                    .firstBuiltAt(Instant.now())
                    .lastRunId(UUID.randomUUID())
                    .lastSuccessAt(Instant.now())
                    .windowUntil(tomorrowStart())
                    .build());
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

        assertThat(data.runs(built)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED);
            assertThat(run.mode()).isEqualTo(TopicRun.Mode.FIRST_BUILD);
            assertThat(json(run.stats()).path("vectors").asInt()).isEqualTo(BUILT);
        });
        // The run is for tomorrow's window, but its topics are born today (UTC), never in the future.
        var topics = data.topics(built);
        assertThat(topics).isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.born()).isEqualTo(LocalDate.now(ZoneOffset.UTC)));
        assertThat(data.state(built).windowUntil()).isEqualTo(tomorrowStart());
        // The window is under maxVectors: the run labels it whole, and its pass finds nothing left.
        assertThat(data.state(built).labelsComplete()).isTrue();
        for (var skipped : List.of(withList, small, quiet, notOptedIn, deleted)) {
            assertThat(data.runs(skipped)).isEmpty();
        }

        firstBuildService.run(Instant.now());

        assertThat(data.runs(built)).hasSize(1);
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

        var timedOut = data.runs(first).getFirst().status() == TopicRun.Status.FAILED ? first : second;
        var saved = timedOut.equals(first) ? second : first;
        assertThat(data.runs(saved)).singleElement().extracting(TopicRun::status).isEqualTo(TopicRun.Status.SAVED);
        assertThat(data.topics(saved)).isNotEmpty();
        // The interrupted build may still get its slow answer: past it, a save that raced the timeout would show.
        Awaitility.await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(data.runs(timedOut)).singleElement().satisfies(run -> {
                assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
                assertThat(run.errorCode()).isEqualTo("timeout");
                assertThat(run.errorMessage()).contains("projectTimeout");
            });
            assertThat(data.topics(timedOut)).isEmpty();
            assertThat(data.prototypes(timedOut)).isEmpty();
            assertThat(data.state(timedOut)).isNull();
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
        insertRun(savedRun(workspaceId, savedBetween, Instant.now().minus(Duration.ofDays(2))));
        insertFailedRun(workspaceId, savedBetween, Duration.ofMinutes(70));
        stubAnswer(FIXTURE.answer);

        assertThat(firstBuildService.candidates(tomorrow(), Instant.now()))
                .extracting(TopicsFirstBuildService.Candidate::projectId)
                .containsExactlyInAnyOrder(failedAnHourAgo, savedBetween);

        runUntilBuilt(failedAnHourAgo, 2);
        runUntilBuilt(savedBetween, 6);

        assertThat(data.runs(failedAnHourAgo)).hasSize(2).last()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED));
        assertThat(data.runs(savedBetween)).last()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED));
        assertThat(data.runs(failedRecently)).hasSize(1);
        assertThat(data.runs(failedTwiceRecently)).hasSize(2);

        try (var response = rebuild(workspaceId, failedRecently, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.runs(failedRecently))
                .hasSize(2).last().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
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
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.runs(projectId))
                .singleElement().satisfies(run -> {
                    assertThat(run.runId().toString()).isEqualTo(runId);
                    assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED);
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
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.runs(projectId))
                .singleElement().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
        assertThat(data.topics(projectId)).isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.born()).isEqualTo(fixtureNight()));
    }

    @Test
    @DisplayName("a project whose vectors backfill has not drained is built neither by the job nor by hand (409), "
            + "while another project of the workspace is; ignore_backfill=true builds it by hand, and once its row "
            + "drains the job does")
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

        assertThat(data.runs(waiting)).isEmpty();
        assertThat(data.runs(byHand)).isEmpty();
        try (var response = rebuild(workspaceId, byHand, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.readEntity(String.class)).contains("vectors backfill");
        }
        assertThat(data.runs(byHand)).isEmpty();
        try (var response = rebuild(workspaceId, byHand, tomorrow().toString(), true)) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.runs(byHand))
                .singleElement().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
        assertThat(data.runs(waiting)).isEmpty();

        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).record(List.of(TopicVectorBackfill.Progress.builder()
                    .workspaceId(workspaceId).projectId(waiting).drainedAt(Instant.now()).build()));
            return null;
        });
        runUntilBuilt(waiting);

        assertThat(data.runs(waiting)).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED));
    }

    @Test
    @DisplayName("a workspace opted in to ai-labels but not to topics is built neither by the job nor by hand")
    void topicsOptIn() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT, tomorrowStart());
        stubAnswer(FIXTURE.answer);

        firstBuildService.run(Instant.now());

        assertThat(data.runs(projectId)).isEmpty();
        try (var response = rebuild(workspaceId, projectId, tomorrow().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        assertThat(data.runs(projectId)).isEmpty();
    }

    @Test
    @DisplayName("by hand, a window under minVectors, or another workspace's project, is refused")
    void byHandFollowsTheJobsRules() {
        var workspaceId = enabledWorkspace();
        var small = UUID.randomUUID();
        data.seed(workspaceId, small, BUILT, SMALL);
        var foreign = UUID.randomUUID();
        data.seed(enabledWorkspace(), foreign, 0, BUILT);

        try (var response = rebuild(workspaceId, small, fixtureNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = rebuild(workspaceId, foreign, fixtureNight().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        assertThat(data.runs(small)).isEmpty();
        assertThat(data.runs(foreign)).isEmpty();
    }

    @Test
    @DisplayName("housekeeping leaves a run younger than a run can last: it may be a live run started by hand")
    void housekeepingSparesALiveRun() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        insertRun(runningRun(workspaceId, projectId, Instant.now().minus(Duration.ofMinutes(2))));

        firstBuildService.run(Instant.now());

        assertThat(data.runs(projectId)).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.RUNNING));
    }

    @Test
    @DisplayName("a run left running by a pod that died is failed as abandoned")
    void abandonedRun() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        insertRun(runningRun(workspaceId, projectId, Instant.now().minus(Duration.ofDays(1))));

        firstBuildService.run(Instant.now());

        assertThat(data.runs(projectId)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
            assertThat(run.errorCode()).isEqualTo("abandoned");
        });
    }

    @Test
    @DisplayName("housekeeping deletes the runs started before the runs retention and keeps newer ones")
    void housekeepingDeletesOldRuns() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var cut = Instant.now().minus(opikConfig.getTopics().getBuild().getRunsRetention().toJavaDuration());
        var old = savedRun(workspaceId, projectId, cut.minus(Duration.ofMinutes(1)));
        var recent = savedRun(workspaceId, projectId, Instant.now().minus(Duration.ofDays(1)));
        insertRun(old);
        insertRun(recent);

        firstBuildService.run(Instant.now());

        assertThat(data.runs(projectId)).extracting(TopicRun::runId).containsExactly(recent.runId());
    }

    @Test
    @DisplayName("a job run finishes the labelling pass of a saved list whose labels are not complete, and marks "
            + "them complete; a project of a workspace not opted in or with AI labels off is left as it is")
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
        var runId = data.state(unfinished).lastRunId();

        firstBuildService.run(Instant.now());

        assertThat(data.traceTopics(workspaceId, unfinished)).hasSize(BUILT)
                .allSatisfy(row -> assertThat(row.runId()).isEqualTo(runId.toString()));
        assertThat(data.state(unfinished).labelsComplete()).isTrue();
        for (var left : List.of(notOptedIn, aiLabelsOff)) {
            assertThat(data.state(left).labelsComplete()).isFalse();
        }
        assertThat(data.traceTopics(notOptedInWorkspace, notOptedIn)).isEmpty();
        assertThat(data.traceTopics(aiLabelsOffWorkspace, aiLabelsOff)).isEmpty();
    }

    @Test
    @DisplayName("a job run deletes the topics, prototypes, state and runs of a project that no longer exists, and "
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
        assertThat(data.prototypes(deleted)).isEmpty();
        assertThat(data.state(deleted)).isNull();
        assertThat(data.runs(deleted)).isEmpty();
        assertThat(data.topics(kept)).hasSize(1);
        assertThat(data.prototypes(kept)).hasSize(2);
        assertThat(data.state(kept)).isNotNull();
        assertThat(data.runs(kept)).hasSize(1);
    }

    /**
     * Runs the job's run until the project has {@code runs} runs: a project whose lock a run by hand of an earlier test
     * still holds for a moment is skipped, and the next run takes it.
     */
    private void runUntilBuilt(UUID projectId, int runs) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            firstBuildService.run(Instant.now());
            assertThat(data.runs(projectId)).hasSize(runs);
        });
    }

    private void runUntilBuilt(UUID projectId) {
        runUntilBuilt(projectId, 1);
    }

    /** A failed first build of the project, started a minute before it finished {@code finishedAgo} ago. */
    private void insertFailedRun(String workspaceId, UUID projectId, Duration finishedAgo) {
        var finishedAt = Instant.now().minus(finishedAgo);
        insertRun(TopicRun.builder().runId(UUID.randomUUID()).workspaceId(workspaceId).projectId(projectId)
                .mode(TopicRun.Mode.FIRST_BUILD).status(TopicRun.Status.FAILED).errorCode("qwen_error")
                .startedAt(finishedAt.minus(Duration.ofMinutes(1))).finishedAt(finishedAt).build());
    }

    private static TopicRun runningRun(String workspaceId, UUID projectId, Instant startedAt) {
        return TopicRun.builder().runId(UUID.randomUUID()).workspaceId(workspaceId).projectId(projectId)
                .mode(TopicRun.Mode.FIRST_BUILD).status(TopicRun.Status.RUNNING).startedAt(startedAt).build();
    }

    private static TopicRun savedRun(String workspaceId, UUID projectId, Instant startedAt) {
        return TopicRun.builder().runId(UUID.randomUUID()).workspaceId(workspaceId).projectId(projectId)
                .mode(TopicRun.Mode.FIRST_BUILD).status(TopicRun.Status.SAVED).startedAt(startedAt)
                .finishedAt(startedAt.plus(Duration.ofMinutes(1))).build();
    }

    /** Inserts the run, and its project's row: the job deletes the runs of projects that no longer exist. */
    private void insertRun(TopicRun run) {
        data.project(run.workspaceId(), run.projectId());
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(run);
            return null;
        });
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

    /** The project's {@code topic_vector_backfills} row as the vectors tick writes it, started now. */
    private void backfill(String workspaceId, UUID projectId, boolean drained) {
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicVectorBackfillDAO.class);
            dao.start(List.of(TopicVectorBackfill.builder().workspaceId(workspaceId).projectId(projectId)
                    .startedAt(now).checkedAt(now).lastDoneAt(now).build()));
            if (drained) {
                dao.record(List.of(TopicVectorBackfill.Progress.builder().workspaceId(workspaceId)
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
