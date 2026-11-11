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
import com.comet.opik.api.resources.v1.jobs.TopicsNightlyJob;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
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
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.domain.topics.TopicsTestData.json;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The topics nightly job against real ClickHouse, MySQL and Redis, with opik-inference's {@code /v1/generate} stubbed
 * in WireMock. Tests seed {@code topic_vectors} with the fixture's traces (its texts, vectors and times, so the night
 * is the fixture's) and call {@link TopicsNightlyService#runNight(Instant)}; the scheduled job fires at 02:00 UTC only.
 * The first-build job's tests call {@link TopicsNightlyService#runFirstBuilds(Instant)}; the scheduled one fires at
 * startup, before any workspace is opted in, and then hourly.
 */
@DisplayName("topics nightly job")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsNightlyJobTest {

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
    private TopicsNightlyService nightlyService;
    private TopicsTestData data;
    private GuiceJobManager jobManager;
    private LockService lockService;
    private ClientSupport client;
    private String baseUrl;

    @BeforeAll
    void beforeAll(ClientSupport client, OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, GuiceJobManager jobManager, LockService lockService,
            @Jit TopicsNightlyService nightlyService, @Jit TopicVectorsDAO vectorsDAO) {
        this.client = client;
        this.opikConfig = opikConfig;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.template = template;
        this.jobManager = jobManager;
        this.lockService = lockService;
        this.nightlyService = nightlyService;
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
    @DisplayName("enabled, the job is scheduled on the nightly cron")
    void jobIsScheduled() throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var jobKeys = new ArrayList<JobKey>();
        for (var key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            if (scheduler.getJobDetail(key).getJobClass() == TopicsNightlyJob.class) {
                jobKeys.add(key);
            }
        }

        assertThat(jobKeys).singleElement().satisfies(key -> assertThat(scheduler.getTriggersOfJob(key))
                .singleElement()
                .isInstanceOfSatisfying(CronTrigger.class,
                        trigger -> assertThat(trigger.getCronExpression()).isEqualTo("0 0 2 * * ?")));
    }

    @Test
    @DisplayName("enabled, the first-build job is scheduled on its interval")
    void firstBuildJobIsScheduled() throws SchedulerException {
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
    @DisplayName("builds only the enabled, existing project with minVectors, saves the run, and skips it the next night")
    void firstBuild() {
        var workspaceId = enabledWorkspace();
        var built = UUID.randomUUID();
        var small = UUID.randomUUID();
        var disabled = UUID.randomUUID();
        var deleted = UUID.randomUUID();
        data.seed(workspaceId, built, 0, BUILT);
        data.seed(workspaceId, small, BUILT, SMALL);
        data.seed(UUID.randomUUID().toString(), disabled, 0, BUILT);
        data.seed(workspaceId, deleted, 0, BUILT);
        template.inTransaction(WRITE,
                handle -> handle.execute("DELETE FROM projects WHERE id = ?", deleted.toString()));
        stubAnswer(FIXTURE.answer);

        var result = nightlyService.runNight(start());

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(data.runs(small)).isEmpty();
        assertThat(data.runs(disabled)).isEmpty();
        assertThat(data.runs(deleted)).isEmpty();

        // What a build saves is TopicsBuildTest's: here, that the night built this project and recorded it.
        var run = data.runs(built);
        assertThat(run).singleElement().satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(TopicRun.Status.SAVED);
            assertThat(saved.mode()).isEqualTo(TopicRun.Mode.FIRST_BUILD);
        });
        var runId = run.getFirst().runId();

        // The night's record, which the state gauges read.
        template.inTransaction(READ_ONLY, handle -> {
            var nights = handle.attach(TopicNightDAO.class);
            assertThat(nights.findByNight(night()).outcome()).isEqualTo(TopicNight.Outcome.FINISHED);
            assertThat(nights.findProjects(night())).filteredOn(row -> workspaceId.equals(row.workspaceId()))
                    .extracting(TopicNightProject::projectId, TopicNightProject::decision, TopicNightProject::reason,
                            TopicNightProject::runId)
                    .containsExactlyInAnyOrder(
                            tuple(built, "first_build", null, runId),
                            tuple(small, "skip", "small", null),
                            tuple(deleted, "skip", "deleted", null));
            return null;
        });

        var topics = data.topics(built);
        assertThat(topics).isNotEmpty();

        // The next night: the project has a list, and stage 2a has no nightly update.
        var again = nightlyService.runNight(start().plus(Duration.ofDays(1)));

        assertThat(again.saved()).isZero();
        assertThat(again.skipped()).isGreaterThanOrEqualTo(1);
        assertThat(data.runs(built)).hasSize(1);
        assertThat(data.topics(built)).hasSameSizeAs(topics);
    }

    @Test
    @DisplayName("a run that outlasts projectTimeout fails with timeout and writes nothing; the night's next project "
            + "still runs")
    void projectTimeout() {
        var workspaceId = enabledWorkspace();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        data.seed(workspaceId, first, 0, BUILT);
        data.seed(workspaceId, second, 0, BUILT);
        // The night's first Qwen call answers after the project timeout; every later call answers at once.
        stubAnswerInScenario(FIXTURE.answer, 4_000, Scenario.STARTED, "answered");
        stubAnswerInScenario(FIXTURE.answer, 0, "answered", "answered");
        var nightly = opikConfig.getTopics().getNightly();
        var projectTimeout = nightly.getProjectTimeout();
        nightly.setProjectTimeout(io.dropwizard.util.Duration.seconds(2));
        TopicsNightlyService.NightResult result;
        try {
            result = nightlyService.runNight(start());
        } finally {
            nightly.setProjectTimeout(projectTimeout);
        }

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
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
            assertThat(data.counts(timedOut)).isEmpty();
            assertThat(data.traceTopics(workspaceId, timedOut)).isEmpty();
        });
    }

    @Test
    @DisplayName("the internal endpoint starts a first build now, and refuses a project that has a list")
    void rebuildByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        // The by-hand night is today: its window is the 30 days before today's start.
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

        try (var response = rebuild(workspaceId, projectId, night().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.runs(projectId))
                .singleElement().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
        assertThat(data.topics(projectId)).isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.born()).isEqualTo(night()));
    }

    @Test
    @DisplayName("a workspace opted in to ai-labels but not to topics is neither built by the night nor by hand")
    void topicsOptIn() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(FIXTURE.answer);

        assertThat(nightlyService.runNight(start()).saved()).isZero();
        assertThat(data.runs(projectId)).isEmpty();
        try (var response = rebuild(workspaceId, projectId, night().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        assertThat(data.runs(projectId)).isEmpty();
    }

    @Test
    @DisplayName("by hand, a window under minVectors, or another workspace's project, is refused")
    void byHandFollowsTheNightsRules() {
        var workspaceId = enabledWorkspace();
        var small = UUID.randomUUID();
        data.seed(workspaceId, small, BUILT, SMALL);
        var foreign = UUID.randomUUID();
        data.seed(enabledWorkspace(), foreign, 0, BUILT);

        try (var response = rebuild(workspaceId, small, night().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        try (var response = rebuild(workspaceId, foreign, night().toString())) {
            assertThat(response.getStatus()).isEqualTo(409);
        }
        assertThat(data.runs(small)).isEmpty();
        assertThat(data.runs(foreign)).isEmpty();
    }

    @Test
    @DisplayName("the night endpoint runs the whole night now and builds the eligible project")
    void runNightByHand() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        stubAnswer(FIXTURE.answer);

        try (var response = runNight(night().toString())) {
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.readEntity(JsonNode.class).path("night").asText()).isEqualTo(night().toString());
        }
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(data.runs(projectId))
                .singleElement().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
        // Released when the night ended: a second night can start.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (var response = runNight(night().toString())) {
                assertThat(response.getStatus()).isEqualTo(202);
            }
        });
        try (var response = runNight("tomorrow")) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("housekeeping leaves a run younger than a run can last: it may be a live run started by hand")
    void housekeepingSparesALiveRun() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder().runId(runId).workspaceId(workspaceId)
                    .projectId(projectId).mode(TopicRun.Mode.FIRST_BUILD).status(TopicRun.Status.RUNNING)
                    .startedAt(Instant.now().minus(Duration.ofMinutes(2))).build());
            return null;
        });

        nightlyService.runNight(Instant.now());

        assertThat(data.runs(projectId)).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.RUNNING));
    }

    @Test
    @DisplayName("a run left running by an earlier night is failed as abandoned")
    void abandonedRun() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder().runId(runId).workspaceId(workspaceId)
                    .projectId(projectId).mode(TopicRun.Mode.FIRST_BUILD).status(TopicRun.Status.RUNNING)
                    .startedAt(Instant.now().minus(Duration.ofDays(1))).build());
            return null;
        });

        nightlyService.runNight(Instant.now());

        assertThat(data.runs(projectId)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.FAILED);
            assertThat(run.errorCode()).isEqualTo("abandoned");
        });
    }

    @Test
    @DisplayName("a project whose last 3 runs failed is paused for 7 nights from the last failure, and a run by hand "
            + "ignores the pause")
    void pauseAfterThreeFailures() {
        var workspaceId = enabledWorkspace();
        var today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        var paused = UUID.randomUUID();
        var lastPausedNight = UUID.randomUUID();
        var pauseOver = UUID.randomUUID();
        var twoFailures = UUID.randomUUID();
        var savedBetween = UUID.randomUUID();
        for (var projectId : List.of(paused, lastPausedNight, pauseOver, twoFailures, savedBetween)) {
            data.seed(workspaceId, projectId, 0, BUILT, today);
        }
        insertRun(workspaceId, paused, 3, TopicRun.Status.FAILED, "qwen_error");
        insertRun(workspaceId, paused, 2, TopicRun.Status.FAILED, "qwen_error");
        insertRun(workspaceId, paused, 1, TopicRun.Status.FAILED, "abandoned");
        for (int daysAgo : List.of(9, 8, 7)) {
            insertRun(workspaceId, lastPausedNight, daysAgo, TopicRun.Status.FAILED, "timeout");
        }
        for (int daysAgo : List.of(10, 9, 8)) {
            insertRun(workspaceId, pauseOver, daysAgo, TopicRun.Status.FAILED, "timeout");
        }
        for (int daysAgo : List.of(2, 1)) {
            insertRun(workspaceId, twoFailures, daysAgo, TopicRun.Status.FAILED, "timeout");
        }
        insertRun(workspaceId, savedBetween, 4, TopicRun.Status.FAILED, "timeout");
        insertRun(workspaceId, savedBetween, 3, TopicRun.Status.SAVED, null);
        insertRun(workspaceId, savedBetween, 2, TopicRun.Status.FAILED, "timeout");
        insertRun(workspaceId, savedBetween, 1, TopicRun.Status.FAILED, "timeout");
        stubAnswer(FIXTURE.answer);

        var result = nightlyService.runNight(today.plus(Duration.ofHours(2)));

        assertThat(result.saved()).isEqualTo(3);
        template.inTransaction(READ_ONLY, handle -> {
            assertThat(handle.attach(TopicNightDAO.class).findProjects(LocalDate.ofInstant(today, ZoneOffset.UTC)))
                    .filteredOn(row -> workspaceId.equals(row.workspaceId()))
                    .extracting(TopicNightProject::projectId, TopicNightProject::decision, TopicNightProject::reason)
                    .containsExactlyInAnyOrder(
                            tuple(paused, "skip", "paused"),
                            tuple(lastPausedNight, "skip", "paused"),
                            tuple(pauseOver, "first_build", null),
                            tuple(twoFailures, "first_build", null),
                            tuple(savedBetween, "first_build", null));
            return null;
        });
        assertThat(data.runs(paused)).hasSize(3);
        assertThat(data.runs(lastPausedNight)).hasSize(3);

        try (var response = rebuild(workspaceId, paused)) {
            assertThat(response.getStatus()).isEqualTo(202);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(data.runs(paused))
                .hasSize(4).last().satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED)));
    }

    @Test
    @DisplayName("the first-build job builds a project with minVectors in tomorrow's window, for tomorrow, its topics "
            + "born today; it skips one with a list, with a run in the last 24 h, paused, or under minVectors")
    void firstBuildsByDay() {
        var workspaceId = enabledWorkspace();
        var tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        var tomorrowStart = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant();
        var built = UUID.randomUUID();
        var withList = UUID.randomUUID();
        var recentRun = UUID.randomUUID();
        var paused = UUID.randomUUID();
        var small = UUID.randomUUID();
        for (var projectId : List.of(built, withList, recentRun, paused)) {
            data.seed(workspaceId, projectId, 0, BUILT, tomorrowStart);
        }
        data.seed(workspaceId, small, BUILT, SMALL, tomorrowStart);
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicDAO.class).insertState(TopicProjectState.builder()
                    .projectId(withList)
                    .workspaceId(workspaceId)
                    .rebuilds(1)
                    .firstBuiltAt(Instant.now())
                    .lastRunId(UUID.randomUUID())
                    .lastSuccessAt(Instant.now())
                    .windowUntil(tomorrowStart)
                    .build());
            return null;
        });
        insertRun(workspaceId, recentRun, 0, TopicRun.Status.FAILED, "qwen_error");
        for (int daysAgo : List.of(4, 3, 2)) {
            insertRun(workspaceId, paused, daysAgo, TopicRun.Status.FAILED, "timeout");
        }
        stubAnswer(FIXTURE.answer);

        // The job's own filters: a run by hand would refuse the projects with a list or under minVectors anyway.
        assertThat(nightlyService.firstBuildCandidates(tomorrow, Instant.now()))
                .containsExactly(new TopicsNightlyService.Candidate(workspaceId, built, BUILT));

        runFirstBuildsUntilBuilt(built);

        assertThat(data.runs(built)).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED);
            assertThat(json(run.stats()).path("vectors").asInt()).isEqualTo(BUILT);
        });
        // The run is for tomorrow's night, but its topics are born today (UTC), never in the future.
        var today = LocalDate.now(ZoneOffset.UTC);
        assertThat(data.topics(built)).isNotEmpty().allSatisfy(topic -> assertThat(topic.born()).isEqualTo(today));
        assertThat(data.topics(built)).allSatisfy(topic -> assertThat(topic.born()).isBeforeOrEqualTo(today));
        assertThat(data.state(built).windowUntil()).isEqualTo(tomorrowStart);
        assertThat(data.runs(withList)).isEmpty();
        assertThat(data.runs(recentRun)).hasSize(1);
        assertThat(data.runs(paused)).hasSize(3);
        assertThat(data.runs(small)).isEmpty();
    }

    @Test
    @DisplayName("the first-build job starts nothing while the night holds its lock, and builds once it is released")
    void firstBuildsWaitForTheNight() throws InterruptedException {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT,
                LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        stubAnswer(FIXTURE.answer);
        var nightStarted = new CountDownLatch(1);
        var nightEnds = new CountDownLatch(1);
        var night = lockService.bestEffortLock(
                TopicsNightlyService.NIGHT_LOCK,
                Mono.fromCallable(() -> {
                    nightStarted.countDown();
                    nightEnds.await();
                    return true;
                }).subscribeOn(Schedulers.boundedElastic()),
                Mono.empty(),
                Duration.ofMinutes(1),
                Duration.ZERO)
                .subscribe();
        try {
            assertThat(nightStarted.await(10, TimeUnit.SECONDS)).isTrue();

            nightlyService.runFirstBuilds(Instant.now());

            assertThat(data.runs(projectId)).isEmpty();
        } finally {
            nightEnds.countDown();
        }
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> Boolean.FALSE.equals(lockService.isLocked(TopicsNightlyService.NIGHT_LOCK).block()));
        night.dispose();

        runFirstBuildsUntilBuilt(projectId);

        assertThat(data.runs(projectId)).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(TopicRun.Status.SAVED));
    }

    @Test
    @DisplayName("housekeeping deletes the runs started before the runs retention with their counts, a run saved after "
            + "the cut included, and keeps newer ones")
    void housekeepingDeletesOldRunCounts() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var cut = Instant.now().minus(Duration.ofDays(90));
        var old = UUID.randomUUID();
        // Started just before the cut and saved just after it: its counts must go with the run.
        var acrossTheCut = UUID.randomUUID();
        var recent = UUID.randomUUID();
        template.inTransaction(WRITE, handle -> {
            var runDAO = handle.attach(TopicRunDAO.class);
            runDAO.insert(savedRun(old, workspaceId, projectId, cut.minus(Duration.ofDays(1))));
            runDAO.insert(savedRun(acrossTheCut, workspaceId, projectId, cut.minus(Duration.ofMinutes(1))));
            runDAO.insert(savedRun(recent, workspaceId, projectId, Instant.now().minus(Duration.ofDays(1))));
            runDAO.insertCounts(List.of(
                    otherCount(old, workspaceId, projectId, cut.minus(Duration.ofDays(1))),
                    otherCount(acrossTheCut, workspaceId, projectId, cut.plus(Duration.ofMinutes(1))),
                    otherCount(recent, workspaceId, projectId, Instant.now().minus(Duration.ofDays(1)))));
            return null;
        });

        nightlyService.runNight(Instant.now());

        assertThat(data.runs(projectId)).extracting(TopicRun::runId).containsExactly(recent);
        assertThat(data.counts(projectId)).extracting(TopicRunCount::runId).containsExactly(recent);
    }

    private static TopicRun savedRun(UUID runId, String workspaceId, UUID projectId, Instant startedAt) {
        return TopicRun.builder()
                .runId(runId)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .mode(TopicRun.Mode.FIRST_BUILD)
                .status(TopicRun.Status.SAVED)
                .startedAt(startedAt)
                .build();
    }

    /**
     * Runs the first-build job's run until it has built the project: a run stops at the first project that cannot
     * start, as when a run by hand or a night of an earlier test still holds its lock for a moment.
     */
    private void runFirstBuildsUntilBuilt(UUID projectId) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            nightlyService.runFirstBuilds(Instant.now());
            assertThat(data.runs(projectId)).isNotEmpty();
        });
    }

    /** A finished run of the project, started at 02:00 UTC {@code daysAgo} days before today. */
    private void insertRun(String workspaceId, UUID projectId, int daysAgo, TopicRun.Status status,
            String errorCode) {
        var startedAt = LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).atStartOfDay(ZoneOffset.UTC).toInstant()
                .plus(Duration.ofHours(2));
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder().runId(UUID.randomUUID())
                    .workspaceId(workspaceId).projectId(projectId).mode(TopicRun.Mode.FIRST_BUILD).status(status)
                    .startedAt(startedAt).finishedAt(startedAt.plus(Duration.ofMinutes(1))).errorCode(errorCode)
                    .build());
            return null;
        });
    }

    private static TopicRunCount otherCount(UUID runId, String workspaceId, UUID projectId, Instant savedAt) {
        return TopicRunCount.builder()
                .runId(runId)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .topicId(TraceTopic.OTHER)
                .traces(10)
                .savedAt(savedAt)
                .build();
    }

    /** The night after the fixture's last trace: its window is the fixture's. */
    private static Instant start() {
        return FIXTURE.night.plus(Duration.ofHours(2));
    }

    private static LocalDate night() {
        return LocalDate.ofInstant(FIXTURE.night, ZoneOffset.UTC);
    }

    private String enabledWorkspace() {
        var workspaceId = aiLabelsOnlyWorkspace();
        var topics = opikConfig.getTopics();
        var listed = new TreeSet<>(topics.enabledWorkspaceIds());
        listed.add(workspaceId);
        topics.setEnabledWorkspaces(String.join(",", listed));
        return workspaceId;
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

    private Response runNight(String night) {
        var target = client.target("%s/v1/internal/topics/nights/run".formatted(baseUrl));
        return (night == null ? target : target.queryParam("night", night))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    private Response rebuild(String workspaceId, UUID projectId) {
        return rebuild(workspaceId, projectId, null);
    }

    private Response rebuild(String workspaceId, UUID projectId, String night) {
        var target = client.target("%s/v1/internal/topics/projects/%s/run".formatted(baseUrl, projectId))
                .queryParam("workspace_id", workspaceId);
        return (night == null ? target : target.queryParam("night", night))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(""));
    }

    private void stubAnswer(JsonNode output) {
        stubAnswer(output, 0);
    }

    private void stubAnswer(JsonNode output, int delayMillis) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(output, delayMillis)));
    }

    /** As {@link #stubAnswer(JsonNode, int)}, matched only in the scenario's {@code state}, which it then moves on. */
    private void stubAnswerInScenario(JsonNode output, int delayMillis, String state, String nextState) {
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).inScenario("answers").whenScenarioStateIs(state)
                .willSetStateTo(nextState).willReturn(TopicsTestData.answer(output, delayMillis)));
    }
}
