package com.comet.opik.domain.ailabels;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.v1.jobs.AiLabelsJob;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.lock.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tick against real ClickHouse (post-cutover {@code traces}, legacy {@code spans}), MySQL and Redis, with
 * opik-inference stubbed in WireMock. Tests call {@link AiLabelsTickService#tick(Instant)} directly; the scheduled job
 * fires once at startup and then only every hour.
 *
 * <p>Tasks: {@code trace_summary} (the bundled generate task, applying from 2020) and {@code agent_outcome}, a decide
 * task of two questions, so each trace costs 3 engine calls: 2 in a decide job and 1 in a generate job.
 */
@DisplayName("ai-labels tick")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AiLabelsTickTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final String DECIDE_PATH = "/v1/batch/decide";
    private static final String GENERATE_PATH = "/v1/batch/generate";
    private static final String JOB_PATH = "/v1/batch/jobs/";
    private static final String DECIDE_TASK = "agent_outcome";
    private static final String GENERATE_TASK = "trace_summary";
    private static final Set<String> TASKS = Set.of(DECIDE_TASK, GENERATE_TASK);
    // Two decide questions and one generate output.
    private static final int LABELS_PER_TRACE = 3;
    private static final String DECIDE_YAML = """
            name: agent_outcome
            endpoint: decide
            cache: false
            version: 1
            applies_from: 2020-01-01T00:00:00Z
            questions:
              succeeded: {type: noul, instructions: "Did the agent accomplish the user's goal?"}
              topic: {type: choice, instructions: "Main topic?", criteria: {billing: null, technical: null, other: null}}
            """;
    // The sweep and poll cadences are 1 s; the extra half second covers MySQL's clock, which marks a job polled.
    private static final Duration TICK_GAP = Duration.ofMillis(1_500);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(4);
    // ClickHouse stamps updated_at with its own clock, which can drift from ours.
    private static final Duration CLOCK_MARGIN = Duration.ofSeconds(1);

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    // Not reused: the poller, the budget and the opt-in read whole tables, and the traces cutover is destructive.
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, zookeeper, clickHouse).join();
        wireMock = WireMockUtils.startWireMock();
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
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                        new CustomConfig("aiLabels.enabled", "true"),
                        new CustomConfig("aiLabels.inference.url", wireMock.server().baseUrl()),
                        new CustomConfig("aiLabels.inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tasksDir", writeTasks().toString()),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.tickBudget", "30s"),
                        new CustomConfig("aiLabels.sweepInterval", "1s"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("aiLabels.pollInterval", "1s"),
                        new CustomConfig("aiLabels.quietPeriod", "0s"),
                        new CustomConfig("aiLabels.retry.delay", RETRY_DELAY.toSeconds() + "s"),
                        new CustomConfig("aiLabels.sweep.buckets", "1")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private LockService lockService;
    private GuiceJobManager jobManager;
    private AiLabelsTickService tickService;
    private InferenceClient client;
    private AiLabelSweepService sweepService;
    private AiLabelTaskRegistry registry;
    private AiLabelRenderer renderer;
    private AiLabelItemBuilder itemBuilder;
    private AiLabelResultMapper resultMapper;
    private AiLabelStatusDAO statusDAO;
    private AiLabelsDAO labelsDAO;
    private Connection clickHouseConnection;

    private Instant lastTick = Instant.EPOCH;
    private int defaultBudget;
    private int defaultChunkTraces;
    private long defaultRenderBudgetBytes;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template, @Jit LockService lockService,
            GuiceJobManager jobManager, @Jit AiLabelsTickService tickService, @Jit InferenceClient client,
            @Jit AiLabelSweepService sweepService, @Jit AiLabelTaskRegistry registry, @Jit AiLabelRenderer renderer,
            @Jit AiLabelItemBuilder itemBuilder, @Jit AiLabelResultMapper resultMapper,
            @Jit AiLabelStatusDAO statusDAO, @Jit AiLabelsDAO labelsDAO) throws SQLException {
        this.opikConfig = opikConfig;
        this.template = template;
        this.lockService = lockService;
        this.jobManager = jobManager;
        this.tickService = tickService;
        this.client = client;
        this.sweepService = sweepService;
        this.registry = registry;
        this.renderer = renderer;
        this.itemBuilder = itemBuilder;
        this.resultMapper = resultMapper;
        this.statusDAO = statusDAO;
        this.labelsDAO = labelsDAO;
        this.clickHouseConnection = clickHouse.createConnection("");
        this.defaultBudget = config().getBudgetEngineCalls();
        this.defaultChunkTraces = config().getChunkTraces();
        this.defaultRenderBudgetBytes = config().getRenderBudgetBytes();

        // The job fires once at startup: wait for that tick to release the leader lock, so it can't race the tests.
        lockService.bestEffortLock(new LockService.Lock("ai_labels:leader"), Mono.just(true), Mono.empty(),
                Duration.ofSeconds(5), Duration.ofSeconds(60)).block();
        lastTick = Instant.now();
    }

    @AfterAll
    void afterAll() throws SQLException {
        clickHouseConnection.close();
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE");
            handle.execute("DELETE FROM ai_label_jobs");
            return null;
        });
        wireMock.server().resetAll();
        stubSubmit(DECIDE_PATH);
        stubSubmit(GENERATE_PATH);
        // Any job without its own stub is still running.
        wireMock.server().stubFor(get(urlPathMatching(JOB_PATH + ".+")).atPriority(10)
                .willReturn(json(200, jobView("job-running", "generate", "running", List.of(), null))));
    }

    @AfterEach
    void tearDown() {
        config().setBudgetEngineCalls(defaultBudget);
        config().setChunkTraces(defaultChunkTraces);
        config().setRenderBudgetBytes(defaultRenderBudgetBytes);
    }

    @Test
    @DisplayName("enabled, the tick job is scheduled every tickInterval")
    void jobIsScheduled() throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var jobKeys = new ArrayList<JobKey>();
        for (var key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            if (scheduler.getJobDetail(key).getJobClass() == AiLabelsJob.class) {
                jobKeys.add(key);
            }
        }

        assertThat(jobKeys).singleElement().satisfies(key -> assertThat(scheduler.getTriggersOfJob(key))
                .singleElement()
                .isInstanceOfSatisfying(SimpleTrigger.class, trigger -> assertThat(trigger.getRepeatInterval())
                        .isEqualTo(Duration.ofHours(1).toMillis())));
    }

    @Test
    @DisplayName("end to end: submit the expected items, load paged results with a repeat, then sweep finds nothing")
    void endToEnd() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var traceIds = List.of(trace(workspaceId, projectId, 3), trace(workspaceId, projectId, 0),
                trace(workspaceId, projectId, 1));

        tick();

        var expectedStates = render(workspaceId, projectId, traceIds);
        var decide = submits(DECIDE_PATH, workspaceId);
        assertThat(decide).singleElement().satisfies(body -> {
            assertThat(body.path("org_id").asText()).isEqualTo(workspaceId);
            assertThat(body.path("cache").asBoolean()).isFalse();
            assertThat(items(body)).extracting(item -> item.path("item_id").asText())
                    .containsExactlyInAnyOrderElementsOf(traceIds.stream().map(UUID::toString).toList());
            assertThat(items(body)).allSatisfy(item -> {
                assertThat(fieldNames(item.path("questions")))
                        .containsExactly("agent_outcome.succeeded", "agent_outcome.topic");
                assertThat(AiLabelRenderer.canonicalJson(item.path("state")))
                        .isEqualTo(expectedStates.get(UUID.fromString(item.path("item_id").asText())));
            });
        });
        var summary = registry.get(GENERATE_TASK);
        var generate = submits(GENERATE_PATH, workspaceId);
        assertThat(generate).singleElement().satisfies(body -> {
            assertThat(body.path("org_id").asText()).isEqualTo(workspaceId);
            assertThat(items(body)).extracting(item -> item.path("item_id").asText())
                    .containsExactlyInAnyOrderElementsOf(traceIds.stream()
                            .map(traceId -> traceId + ":" + GENERATE_TASK).toList());
            assertThat(items(body)).allSatisfy(item -> {
                var traceId = UUID.fromString(item.path("item_id").asText().split(":")[0]);
                assertThat(item.path("messages").get(0).path("content").asText())
                        .isEqualTo(summary.prompt().system());
                assertThat(item.path("messages").get(1).path("content").asText())
                        .isEqualTo(summary.prompt().user().replace(AiLabelTask.STATE_PLACEHOLDER,
                                expectedStates.get(traceId)));
                assertThat(item.path("schema")).isEqualTo(summary.schema());
                assertThat(item.path("max_tokens").asInt()).isEqualTo(64);
            });
        });

        var jobs = jobs(workspaceId);
        assertThat(jobs).extracting(JobRow::endpoint, JobRow::status, JobRow::itemCount, JobRow::engineCalls)
                .containsExactlyInAnyOrder(tuple("decide", "submitted", 3, 6), tuple("generate", "submitted", 3, 3));
        var jobIdByTask = Map.of(DECIDE_TASK, job(jobs, "decide").jobId(), GENERATE_TASK,
                job(jobs, "generate").jobId());
        assertThat(statuses(workspaceId, traceIds)).hasSize(6).allSatisfy(status -> {
            assertThat(status.status()).isEqualTo(AiLabelStatus.Status.PENDING);
            assertThat(status.jobId()).isEqualTo(jobIdByTask.get(status.task()));
            assertThat(status.attempt()).isOne();
            assertThat(status.taskVersion()).isOne();
            assertThat(status.rendererVersion()).isEqualTo(AiLabelRenderer.RENDERER_VERSION);
        });

        completeJob(job(jobs, "decide"), List.of(traceIds.stream().map(AiLabelsTickTest::decideResult).toList()));
        // The service's cursor can repeat an item across pages; it is loaded once.
        completeJob(job(jobs, "generate"), List.of(
                List.of(generateResult(traceIds.get(0)), generateResult(traceIds.get(1))),
                List.of(generateResult(traceIds.get(1)), generateResult(traceIds.get(2)))));
        tick();

        assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
        assertThat(statuses(workspaceId, traceIds)).hasSize(6).allSatisfy(status -> {
            assertThat(status.status()).isEqualTo(AiLabelStatus.Status.DONE);
            assertThat(status.attempt()).isOne();
            assertThat(status.jobId()).isEqualTo(jobIdByTask.get(status.task()));
        });
        var expectedLabels = traceIds.stream()
                .flatMap(traceId -> List.of(
                        tuple(traceId, DECIDE_TASK, "succeeded", AiLabel.AnswerType.NOUL, null, ""),
                        tuple(traceId, DECIDE_TASK, "topic", AiLabel.AnswerType.CHOICE, "billing", ""),
                        tuple(traceId, GENERATE_TASK, "output", AiLabel.AnswerType.OUTPUT, null,
                                "{\"summary\":\"summary of " + traceId + "\"}"))
                        .stream())
                .toList();
        assertThat(labels(workspaceId, traceIds))
                .extracting(AiLabel::traceId, AiLabel::task, AiLabel::key, AiLabel::answerType, AiLabel::choice,
                        AiLabel::output)
                .containsExactlyInAnyOrderElementsOf(expectedLabels);
        assertThat(labelRowCount(workspaceId)).isEqualTo(expectedLabels.size());

        tick();

        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
        assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
    }

    @Test
    @DisplayName("budget: submissions stop at the budget, and loading frees it for the next tick")
    void budget() {
        config().setBudgetEngineCalls(6);
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var traceIds = IntStream.range(0, 4).mapToObj(i -> trace(workspaceId, projectId, 1)).toList();

        tick();

        // The decide job comes first and fills the budget: 3 traces of 2 questions each.
        assertThat(submits(DECIDE_PATH, workspaceId)).singleElement()
                .satisfies(body -> assertThat(items(body)).hasSize(3));
        assertThat(submits(GENERATE_PATH, workspaceId)).isEmpty();
        assertThat(inFlightEngineCalls()).isEqualTo(6);
        var firstDecided = jobTraceIds(workspaceId, job(jobs(workspaceId), "decide"));

        tick();

        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
        assertThat(submits(GENERATE_PATH, workspaceId)).isEmpty();

        completeJobs(workspaceId);
        tick();

        var lastTrace = traceIds.stream().filter(traceId -> !firstDecided.contains(traceId)).toList();
        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(2).last()
                .satisfies(body -> assertThat(itemIds(body))
                        .containsExactlyElementsOf(lastTrace.stream().map(UUID::toString).toList()));
        assertThat(submits(GENERATE_PATH, workspaceId)).singleElement()
                .satisfies(body -> assertThat(items(body)).hasSize(4));
        assertThat(inFlightEngineCalls()).isEqualTo(6);
    }

    @Test
    @DisplayName("budget: once no trace fits what is left of it, the tick stops rendering")
    void budgetTooSmallForAnyTrace() {
        config().setBudgetEngineCalls(5);
        config().setChunkTraces(2);
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var traceIds = IntStream.range(0, 6).mapToObj(i -> trace(workspaceId, projectId, 0)).toList();
        // Summaries done, so each trace misses only the decide task and costs 2 engine calls.
        statusDAO.insert(traceIds.stream()
                .map(traceId -> AiLabelStatus.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .task(GENERATE_TASK)
                        .traceId(traceId)
                        .traceCreatedAt(Instant.now().minusSeconds(60))
                        .taskVersion(1)
                        .rendererVersion(AiLabelRenderer.RENDERER_VERSION)
                        .status(AiLabelStatus.Status.DONE)
                        .attempt(1)
                        .errorCode("")
                        .jobId("job-earlier")
                        .compactionLevel("full")
                        .build())
                .toList())
                .block();
        var countingRenderer = spy(renderer);

        tick(tickService(template, statusDAO, countingRenderer));

        assertThat(submits(DECIDE_PATH, workspaceId)).singleElement()
                .satisfies(body -> assertThat(items(body)).hasSize(2));
        assertThat(inFlightEngineCalls()).isEqualTo(4);
        // The second chunk finds that the 1 call left fits no trace; rendering the third would be wasted.
        verify(countingRenderer, times(2)).render(eq(workspaceId), anyList());
    }

    @Test
    @DisplayName("fairness: a small workspace is submitted in the first tick, however big the one before it")
    void fairness() {
        // Scaled down from 50k and 10 candidates: 12 traces cost 36 engine calls against a budget of 21, in turns of 4.
        config().setBudgetEngineCalls(21);
        config().setChunkTraces(4);
        var workspaceIds = IntStream.range(0, 2).mapToObj(i -> UUID.randomUUID().toString()).sorted().toList();
        // The sweep orders by workspace id, so the big workspace takes the first turn.
        var big = workspaceIds.get(0);
        var small = workspaceIds.get(1);
        enable(big);
        enable(small);
        var bigProject = UUID.randomUUID();
        var smallProject = UUID.randomUUID();
        IntStream.range(0, 12).forEach(i -> trace(big, bigProject, 0));
        var smallTraces = List.of(trace(small, smallProject, 0), trace(small, smallProject, 0));

        tick();

        assertThat(statuses(small, smallTraces)).hasSize(4)
                .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.PENDING));
        assertThat(jobs(small)).extracting(JobRow::engineCalls).containsExactlyInAnyOrder(4, 2);
        // big: a full turn of 4 traces (12 calls), then what's left of the budget (3 calls) on its second turn.
        assertThat(jobs(big)).extracting(JobRow::engineCalls).containsExactlyInAnyOrder(8, 4, 2, 1);
        assertThat(inFlightEngineCalls()).isEqualTo(21);
    }

    @Test
    @DisplayName("429: only that workspace backs off, until Retry-After; the others proceed")
    void throttled() {
        var throttled = enabledWorkspace();
        var other = enabledWorkspace();
        var throttledTrace = trace(throttled, UUID.randomUUID(), 0);
        var otherTrace = trace(other, UUID.randomUUID(), 0);
        var retryAfter = Duration.ofSeconds(4);
        var stubs = List.of(stubQuotaExceeded(DECIDE_PATH, throttled, retryAfter),
                stubQuotaExceeded(GENERATE_PATH, throttled, retryAfter));

        tick();
        var throttledAt = Instant.now();

        // The submit isn't retried, and the workspace's other job isn't tried.
        assertThat(submits(DECIDE_PATH, throttled)).hasSize(1);
        assertThat(submits(GENERATE_PATH, throttled)).isEmpty();
        assertThat(statuses(throttled, List.of(throttledTrace))).isEmpty();
        assertThat(statuses(other, List.of(otherTrace))).hasSize(2)
                .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.PENDING));

        tick();

        assertThat(submits(DECIDE_PATH, throttled)).hasSize(1);
        assertThat(statuses(throttled, List.of(throttledTrace))).isEmpty();

        stubs.forEach(wireMock.server()::removeStub);
        pauseUntil(throttledAt.plus(retryAfter));
        tick();

        assertThat(submits(DECIDE_PATH, throttled)).hasSize(2);
        assertThat(submits(GENERATE_PATH, throttled)).hasSize(1);
        assertThat(statuses(throttled, List.of(throttledTrace))).hasSize(2)
                .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.PENDING));
    }

    @Nested
    @DisplayName("failures")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Failures {

        @Test
        @DisplayName("a retryable item failure comes back after D, up to X attempts; a non-retryable one is terminal")
        void itemFailures() {
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var retried = trace(workspaceId, projectId, 0);
            var terminal = trace(workspaceId, projectId, 0);
            Function<UUID, ObjectNode> failGenerate = traceId -> errorResult(traceId + ":" + GENERATE_TASK,
                    traceId.equals(retried) ? "deadline_exceeded" : "input_too_long", traceId.equals(retried));

            tick();
            completeJobs(workspaceId, failGenerate);
            tick();
            var firstFailureAt = Instant.now();

            assertThat(statuses(workspaceId, List.of(retried, terminal)))
                    .extracting(AiLabelStatus::traceId, AiLabelStatus::task, AiLabelStatus::status,
                            AiLabelStatus::retryable, AiLabelStatus::attempt, AiLabelStatus::errorCode)
                    .containsExactlyInAnyOrder(
                            tuple(retried, DECIDE_TASK, AiLabelStatus.Status.DONE, false, 1, ""),
                            tuple(retried, GENERATE_TASK, AiLabelStatus.Status.FAILED, true, 1, "deadline_exceeded"),
                            tuple(terminal, DECIDE_TASK, AiLabelStatus.Status.DONE, false, 1, ""),
                            tuple(terminal, GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1, "input_too_long"));

            tick();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);

            pauseUntil(firstFailureAt.plus(RETRY_DELAY).plus(CLOCK_MARGIN));
            tick();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(2).last()
                    .satisfies(body -> assertThat(itemIds(body)).containsExactly(retried + ":" + GENERATE_TASK));
            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
            assertThat(status(workspaceId, retried, GENERATE_TASK))
                    .extracting(AiLabelStatus::status, AiLabelStatus::attempt)
                    .containsExactly(AiLabelStatus.Status.PENDING, 2);

            completeJobs(workspaceId, failGenerate);
            tick();
            var secondFailureAt = Instant.now();

            assertThat(status(workspaceId, retried, GENERATE_TASK))
                    .extracting(AiLabelStatus::status, AiLabelStatus::retryable, AiLabelStatus::attempt)
                    .containsExactly(AiLabelStatus.Status.FAILED, true, 2);

            pauseUntil(secondFailureAt.plus(RETRY_DELAY).plus(CLOCK_MARGIN));
            tick();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(2);
        }

        @Test
        @DisplayName("not_found: the job's pending pairs become retryable job_lost, and come back after D")
        void jobLost() {
            var workspaceId = enabledWorkspace();
            var traceId = trace(workspaceId, UUID.randomUUID(), 0);

            tick();
            jobs(workspaceId).forEach(job -> wireMock.server().stubFor(get(urlPathMatching(JOB_PATH + job.jobId()))
                    .willReturn(error(404, "not_found"))));
            tick();
            var lostAt = Instant.now();

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::status, AiLabelStatus::retryable, AiLabelStatus::attempt,
                            AiLabelStatus::errorCode)
                    .containsOnly(tuple(AiLabelStatus.Status.FAILED, true, 1, AiLabelsTickService.JOB_LOST));

            pauseUntil(lostAt.plus(RETRY_DELAY).plus(CLOCK_MARGIN));
            tick();

            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::status, AiLabelStatus::attempt)
                    .containsOnly(tuple(AiLabelStatus.Status.PENDING, 2));
            assertThat(jobs(workspaceId)).extracting(JobRow::status)
                    .containsExactlyInAnyOrder("loaded", "loaded", "submitted", "submitted");
        }

        @Test
        @DisplayName("400 on submit: every pair in that job is terminal invalid_request; other jobs go ahead")
        void submitRejected() {
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var traceIds = List.of(trace(workspaceId, projectId, 0), trace(workspaceId, projectId, 0));
            wireMock.server().stubFor(post(urlEqualTo(GENERATE_PATH))
                    .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId)))
                    .willReturn(error(400, "invalid_request")));

            tick();
            var rejectedAt = Instant.now();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
            assertThat(jobs(workspaceId)).extracting(JobRow::endpoint).containsExactly("decide");
            assertThat(statuses(workspaceId, traceIds))
                    .extracting(AiLabelStatus::task, AiLabelStatus::status, AiLabelStatus::retryable,
                            AiLabelStatus::attempt, AiLabelStatus::errorCode)
                    .containsExactlyInAnyOrder(
                            tuple(DECIDE_TASK, AiLabelStatus.Status.PENDING, false, 1, ""),
                            tuple(DECIDE_TASK, AiLabelStatus.Status.PENDING, false, 1, ""),
                            tuple(GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1, "invalid_request"),
                            tuple(GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1, "invalid_request"));

            pauseUntil(rejectedAt.plus(RETRY_DELAY).plus(CLOCK_MARGIN));
            tick();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
        }

        @Test
        @DisplayName("a trace over the render budget is terminal render_over_budget, and never submitted")
        void renderOverBudget() {
            config().setRenderBudgetBytes(10);
            var workspaceId = enabledWorkspace();
            var traceId = trace(workspaceId, UUID.randomUUID(), 1);

            tick();

            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::task, AiLabelStatus::status, AiLabelStatus::retryable,
                            AiLabelStatus::attempt, AiLabelStatus::errorCode, AiLabelStatus::compactionLevel)
                    .containsExactlyInAnyOrder(
                            tuple(DECIDE_TASK, AiLabelStatus.Status.FAILED, false, 1,
                                    AiLabelsTickService.RENDER_OVER_BUDGET, AiLabelsTickService.OVER_BUDGET_LEVEL),
                            tuple(GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1,
                                    AiLabelsTickService.RENDER_OVER_BUDGET, AiLabelsTickService.OVER_BUDGET_LEVEL));
            assertThat(submits(DECIDE_PATH, workspaceId)).isEmpty();
            assertThat(submits(GENERATE_PATH, workspaceId)).isEmpty();
        }

        @Test
        @DisplayName("a submit still failing after the client's retries drops the chunk; the next sweep resubmits it")
        void submitFailed() {
            var workspaceId = enabledWorkspace();
            var traceId = trace(workspaceId, UUID.randomUUID(), 0);
            var stubs = List.of(DECIDE_PATH, GENERATE_PATH).stream()
                    .map(path -> wireMock.server().stubFor(post(urlEqualTo(path))
                            .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId)))
                            .willReturn(error(503, "overloaded"))))
                    .toList();

            tick();

            // The client's two retries, then the tick moves on to the next job.
            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(3);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(3);
            assertThat(jobs(workspaceId)).isEmpty();
            assertThat(statuses(workspaceId, List.of(traceId))).isEmpty();

            stubs.forEach(wireMock.server()::removeStub);
            tick();

            assertThat(jobs(workspaceId)).hasSize(2);
            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::status, AiLabelStatus::attempt)
                    .containsOnly(tuple(AiLabelStatus.Status.PENDING, 1));
        }

        @Test
        @DisplayName("an item a terminal job never reports is retryable missing_result")
        void missingResult() {
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var reported = trace(workspaceId, projectId, 0);
            var unreported = trace(workspaceId, projectId, 0);

            tick();
            var jobs = jobs(workspaceId);
            completeJob(job(jobs, "decide"), List.of(List.of(decideResult(reported))));
            completeJob(job(jobs, "generate"),
                    List.of(List.of(generateResult(reported), generateResult(unreported))));
            tick();

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(status(workspaceId, reported, DECIDE_TASK).status()).isEqualTo(AiLabelStatus.Status.DONE);
            assertThat(status(workspaceId, unreported, DECIDE_TASK))
                    .extracting(AiLabelStatus::status, AiLabelStatus::retryable, AiLabelStatus::attempt,
                            AiLabelStatus::errorCode)
                    .containsExactly(AiLabelStatus.Status.FAILED, true, 1, AiLabelsTickService.MISSING_RESULT);
            assertThat(status(workspaceId, unreported, GENERATE_TASK).status()).isEqualTo(AiLabelStatus.Status.DONE);
        }

        @Test
        @DisplayName("a pending pair of a task this pod doesn't know is retryable missing_result, not left pending")
        void unknownTaskPendingRow() {
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var traceId = trace(workspaceId, projectId, 0);

            tick();
            var jobs = jobs(workspaceId);
            var generateJob = job(jobs, "generate");
            statusDAO.insert(List.of(status(workspaceId, traceId, GENERATE_TASK).toBuilder()
                    .task("retired_task")
                    .build())).block();
            completeJobs(workspaceId);
            tick();

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(status(workspaceId, traceId, GENERATE_TASK).status()).isEqualTo(AiLabelStatus.Status.DONE);
            assertThat(status(workspaceId, traceId, "retired_task"))
                    .extracting(AiLabelStatus::status, AiLabelStatus::retryable, AiLabelStatus::jobId,
                            AiLabelStatus::errorCode)
                    .containsExactly(AiLabelStatus.Status.FAILED, true, generateJob.jobId(),
                            AiLabelsTickService.MISSING_RESULT);
        }
    }

    @Nested
    @DisplayName("crash points")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CrashPoints {

        @Test
        @DisplayName("after the 202, before the job row: the next sweep resubmits, and the orphan is never read")
        void crashBeforeJobRow() {
            var workspaceId = enabledWorkspace();
            var traceIds = List.of(trace(workspaceId, UUID.randomUUID(), 1));
            var crashingTemplate = spy(template);
            doThrow(new IllegalStateException("crash")).when(crashingTemplate)
                    .inTransaction(same(WRITE), any(TxAction.class));

            tick(tickService(crashingTemplate, statusDAO, renderer));

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
            assertThat(submits(GENERATE_PATH, workspaceId)).isEmpty();
            assertThat(jobs(workspaceId)).isEmpty();
            assertThat(statuses(workspaceId, traceIds)).isEmpty();

            tick();

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(2);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
            assertConverges(workspaceId, traceIds);
        }

        @Test
        @DisplayName("after the job row, before the pending rows: the job loads nothing, and the next sweep resubmits")
        void crashBeforePendingRows() {
            var workspaceId = enabledWorkspace();
            var traceIds = List.of(trace(workspaceId, UUID.randomUUID(), 1));
            var crashingStatusDAO = failingInsertsOf(AiLabelStatus.Status.PENDING);

            tick(tickService(template, crashingStatusDAO, renderer));

            assertThat(jobs(workspaceId)).extracting(JobRow::endpoint).containsExactly("decide");
            assertThat(statuses(workspaceId, traceIds)).isEmpty();

            // The orphaned job completes, but none of its items has a pending row, so none is written.
            completeJob(job(jobs(workspaceId), "decide"),
                    List.of(traceIds.stream().map(AiLabelsTickTest::decideResult).toList()));
            tick();

            assertThat(labels(workspaceId, traceIds)).isEmpty();
            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(2);
            assertConverges(workspaceId, traceIds);
        }

        @Test
        @DisplayName("labels written, status rows not: the job is loaded again and converges")
        void crashBetweenLabelsAndStatus() {
            var workspaceId = enabledWorkspace();
            var traceIds = List.of(trace(workspaceId, UUID.randomUUID(), 1), trace(workspaceId, UUID.randomUUID(), 0));

            tick();
            completeJobs(workspaceId);
            tick(tickService(template, failingInsertsOf(AiLabelStatus.Status.DONE), renderer));

            assertThat(labels(workspaceId, traceIds)).hasSize(6);
            assertThat(statuses(workspaceId, traceIds))
                    .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.PENDING));
            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("submitted");

            tick();

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(statuses(workspaceId, traceIds)).hasSize(4)
                    .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.DONE));
            assertThat(labels(workspaceId, traceIds)).hasSize(6);
            assertThat(labelRowCount(workspaceId)).isEqualTo(12);

            tick();

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
        }

        @Test
        @DisplayName("a load interrupted after its first page resumes: settled items are skipped, the rest load")
        void interruptedLoadResumes() {
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var first = trace(workspaceId, projectId, 0);
            var second = trace(workspaceId, projectId, 0);

            tick();
            var jobs = jobs(workspaceId);
            completeJob(job(jobs, "generate"), List.of(List.of(generateResult(first), generateResult(second))));
            var decide = job(jobs, "decide");
            completeJob(decide, List.of(List.of(decideResult(first)), List.of(decideResult(second))));
            // The second page fails once, with an error the client doesn't retry, so the first load stops after page 1.
            var secondPage = urlEqualTo(JOB_PATH + decide.jobId() + "?cursor=1");
            wireMock.server().stubFor(get(secondPage).inScenario("interrupted").whenScenarioStateIs(STARTED)
                    .willReturn(json(400, InferenceJson.MAPPER.createObjectNode().set("error",
                            InferenceJson.MAPPER.createObjectNode().put("code", "invalid_request")
                                    .put("message", "test").put("retryable", false))))
                    .willSetStateTo("recovered"));
            wireMock.server().stubFor(get(secondPage).inScenario("interrupted").whenScenarioStateIs("recovered")
                    .willReturn(json(200, jobView(decide.jobId(), "decide", "completed",
                            List.of(decideResult(second)), null))));

            tick();

            assertThat(status(workspaceId, first, DECIDE_TASK).status()).isEqualTo(AiLabelStatus.Status.DONE);
            assertThat(status(workspaceId, second, DECIDE_TASK).status()).isEqualTo(AiLabelStatus.Status.PENDING);

            pauseUntil(Instant.now().plusMillis(1_500));
            tick();

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(statuses(workspaceId, List.of(first, second))).hasSize(4)
                    .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.DONE));
        }

        /** Completes whatever is in flight, loads it, and checks a further sweep finds nothing. */
        private void assertConverges(String workspaceId, List<UUID> traceIds) {
            completeJobs(workspaceId);
            tick();

            assertThat(statuses(workspaceId, traceIds)).hasSize(traceIds.size() * TASKS.size())
                    .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.DONE));
            assertThat(labels(workspaceId, traceIds)).hasSize(traceIds.size() * LABELS_PER_TRACE);

            var decideSubmits = submits(DECIDE_PATH, workspaceId).size();
            var generateSubmits = submits(GENERATE_PATH, workspaceId).size();
            tick();

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(decideSubmits);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(generateSubmits);
        }

        private AiLabelStatusDAO failingInsertsOf(AiLabelStatus.Status status) {
            var crashing = spy(statusDAO);
            doAnswer(invocation -> {
                List<AiLabelStatus> rows = invocation.getArgument(0);
                return rows.stream().anyMatch(row -> row.status() == status)
                        ? Mono.error(new IllegalStateException("crash"))
                        : invocation.callRealMethod();
            }).when(crashing).insert(anyList());
            return crashing;
        }
    }

    @Test
    @DisplayName("leader: of two jobs contending on the leader lock, only one ticks")
    void leader() throws InterruptedException {
        var contended = mock(AiLabelsTickService.class);
        when(contended.leaderLease()).thenReturn(Duration.ofSeconds(30));
        var ticks = new AtomicInteger();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            ticks.incrementAndGet();
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(contended).tick(any());
        var first = new AiLabelsJob(config(), contended, lockService);
        var second = new AiLabelsJob(config(), contended, lockService);

        first.doJob(null);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        second.doJob(null);

        Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> ticks.get() == 1);

        release.countDown();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            second.doJob(null);
            assertThat(ticks.get()).isGreaterThanOrEqualTo(2);
        });
    }

    private AiLabelsConfig config() {
        return opikConfig.getAiLabels();
    }

    private AiLabelsTickService tickService(TransactionTemplate template, AiLabelStatusDAO statusDAO,
            AiLabelRenderer renderer) {
        return new AiLabelsTickService(config(), template, client, lockService, sweepService, registry, renderer,
                itemBuilder, resultMapper, statusDAO, labelsDAO);
    }

    private void tick() {
        tick(tickService);
    }

    private void tick(AiLabelsTickService service) {
        pauseUntil(lastTick.plus(TICK_GAP));
        lastTick = Instant.now();
        service.tick(lastTick);
    }

    private static void pauseUntil(Instant instant) {
        var wait = Duration.between(Instant.now(), instant);
        if (wait.isPositive()) {
            Mono.delay(wait).block();
        }
    }

    // --- Fixtures ---

    private static Path writeTasks() {
        try (var bundled = AiLabelsTickTest.class.getClassLoader()
                .getResourceAsStream(AiLabelTaskLoader.CLASSPATH_DIRECTORY + "/trace_summary.yaml")) {
            var summary = new String(bundled.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceFirst("(?m)^applies_from: .*$", "applies_from: 2020-01-01T00:00:00Z");
            var directory = Files.createTempDirectory("ai-labels-tasks");
            Files.writeString(directory.resolve("trace_summary.yaml"), summary);
            Files.writeString(directory.resolve("agent_outcome.yaml"), DECIDE_YAML);
            return directory;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String enabledWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        enable(workspaceId);
        return workspaceId;
    }

    private void enable(String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
    }

    /** An ended trace, created a minute ago, with {@code spans} spans. Inserted into the shard to read back at once. */
    private UUID trace(String workspaceId, UUID projectId, int spans) {
        var traceId = ID_GENERATOR.generateId();
        execute("""
                INSERT INTO %s.%s (workspace_id, project_id, id, name, start_time, end_time, input, output, metadata,
                    created_at, last_updated_at, is_deleted)
                VALUES ('%s', '%s', '%s', 'support chat', now64(6) - toIntervalSecond(60),
                    now64(6) - toIntervalSecond(2), '{"question":"where is my refund?"}',
                    '{"answer":"it is on its way"}', '{"channel":"web"}', now64(6) - toIntervalSecond(60),
                    now64(6) - toIntervalSecond(1), 0)
                """.formatted(DATABASE_NAME, PostCutoverTraces.SHARD, workspaceId, projectId, traceId));
        for (int i = 0; i < spans; i++) {
            execute("""
                    INSERT INTO %s.spans (id, workspace_id, project_id, trace_id, name, type, start_time, end_time,
                        input, output)
                    VALUES ('%s', '%s', '%s', '%s', 'step %d', 'llm', now64(9) - toIntervalSecond(%d),
                        now64(9) - toIntervalSecond(%d), '{"prompt":"step %d"}', '{"text":"done %d"}')
                    """.formatted(DATABASE_NAME, ID_GENERATOR.generateId(), workspaceId, projectId, traceId, i,
                    50 - i, 40 - i, i, i));
        }
        return traceId;
    }

    private void execute(String sql) {
        try (var statement = clickHouseConnection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The canonical rendered state of each trace, as the items must carry it. */
    private Map<UUID, String> render(String workspaceId, UUID projectId, List<UUID> traceIds) {
        var refs = traceIds.stream().map(traceId -> new TraceRef(projectId, traceId, Instant.EPOCH)).toList();
        return renderer.render(workspaceId, refs)
                .cast(RenderOutcome.Rendered.class)
                .collectMap(rendered -> rendered.trace().traceId(),
                        rendered -> AiLabelRenderer.canonicalJson(rendered.state()))
                .block();
    }

    // --- opik-inference stubs ---

    private void stubSubmit(String path) {
        wireMock.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"job_id\":\"job-{{randomValue type='UUID'}}\",\"items\":1}")
                .withTransformers("response-template")));
    }

    private StubMapping stubQuotaExceeded(String path, String workspaceId, Duration retryAfter) {
        return wireMock.server().stubFor(post(urlEqualTo(path))
                .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId)))
                .willReturn(error(429, "quota_exceeded")
                        .withHeader("Retry-After", String.valueOf(retryAfter.toSeconds()))));
    }

    /** Completes every submitted job of the workspace with a success for each of its items. */
    private void completeJobs(String workspaceId) {
        completeJobs(workspaceId, null);
    }

    /** As {@link #completeJobs(String)}, with {@code generateResults} answering the generate items. */
    private void completeJobs(String workspaceId, Function<UUID, ObjectNode> generateResults) {
        for (var job : jobs(workspaceId)) {
            if (!job.status().equals("submitted")) {
                continue;
            }
            var traceIds = jobTraceIds(workspaceId, job);
            var results = traceIds.stream()
                    .map(traceId -> switch (job.endpoint()) {
                        case "decide" -> decideResult(traceId);
                        default -> generateResults == null ? generateResult(traceId) : generateResults.apply(traceId);
                    })
                    .toList();
            completeJob(job, List.of(results));
        }
    }

    /** Stubs the job as completed, its results served as {@code pages} with cursors 0, 1, ... */
    private void completeJob(JobRow job, List<List<ObjectNode>> pages) {
        var path = JOB_PATH + job.jobId();
        wireMock.server().stubFor(get(urlEqualTo(path + "?cursor=0&results=false"))
                .willReturn(json(200, jobView(job.jobId(), job.endpoint(), "completed", List.of(), null))));
        for (int page = 0; page < pages.size(); page++) {
            Integer next = page + 1 < pages.size() ? page + 1 : null;
            wireMock.server().stubFor(get(urlEqualTo(path + "?cursor=" + page))
                    .willReturn(json(200, jobView(job.jobId(), job.endpoint(), "completed", pages.get(page), next))));
        }
    }

    private static ObjectNode jobView(String jobId, String endpoint, String status, List<ObjectNode> results,
            Integer nextCursor) {
        var view = InferenceJson.MAPPER.createObjectNode()
                .put("job_id", jobId)
                .put("endpoint", endpoint)
                .put("status", status)
                .put("cancel_requested", false);
        view.putObject("counts").put("pending", 0).put("ok", results.size()).put("failed", 0).put("cancelled", 0);
        view.putArray("results").addAll(results);
        if (nextCursor == null) {
            view.putNull("next_cursor");
        } else {
            view.put("next_cursor", nextCursor);
        }
        return view;
    }

    private static ObjectNode decideResult(UUID traceId) {
        var result = itemResponse(traceId.toString());
        var answers = ((ObjectNode) result.path("response").path("result")).putObject("answers");
        answers.putObject("agent_outcome.succeeded").put("type", "noul").put("noul", 0.75);
        var topic = answers.putObject("agent_outcome.topic").put("type", "choice").put("choice", "billing")
                .put("confidence", 0.5);
        topic.putObject("probabilities").put("billing", 0.5).put("technical", 0.25).put("other", 0.25);
        return result;
    }

    private static ObjectNode generateResult(UUID traceId) {
        var result = itemResponse(traceId + ":" + GENERATE_TASK);
        ((ObjectNode) result.path("response").path("result")).putObject("output")
                .put("summary", "summary of " + traceId);
        return result;
    }

    private static ObjectNode itemResponse(String itemId) {
        var result = InferenceJson.MAPPER.createObjectNode().put("item_id", itemId);
        var response = result.putObject("response")
                .put("request_id", UUID.randomUUID().toString())
                .put("cache_hit", false)
                .put("latency_ms", 10);
        response.putObject("usage").put("input_tokens", 100).put("output_tokens", 10);
        response.putObject("result");
        return result;
    }

    private static ObjectNode errorResult(String itemId, String code, boolean retryable) {
        var result = InferenceJson.MAPPER.createObjectNode().put("item_id", itemId);
        result.putObject("error").put("code", code).put("message", "failed").put("retryable", retryable);
        return result;
    }

    private static ResponseDefinitionBuilder error(int status, String code) {
        var body = InferenceJson.MAPPER.createObjectNode();
        body.putObject("error").put("code", code).put("message", "error").put("retryable", status == 429);
        return json(status, body);
    }

    private static ResponseDefinitionBuilder json(int status, JsonNode body) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body.toString());
    }

    private List<JsonNode> submits(String path, String workspaceId) {
        return wireMock.server()
                .findAll(postRequestedFor(urlEqualTo(path))
                        .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId))))
                .stream()
                .map(request -> {
                    try {
                        return InferenceJson.MAPPER.readTree(request.getBodyAsString());
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                })
                .toList();
    }

    private static List<JsonNode> items(JsonNode body) {
        return StreamSupport.stream(body.path("items").spliterator(), false).toList();
    }

    private static List<String> itemIds(JsonNode body) {
        return items(body).stream().map(item -> item.path("item_id").asText()).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // --- State ---

    record JobRow(String jobId, String endpoint, String status, int itemCount, int engineCalls) {
    }

    private List<JobRow> jobs(String workspaceId) {
        return template.inTransaction(READ_ONLY, handle -> handle.createQuery("""
                SELECT job_id, endpoint, status, item_count, engine_calls FROM ai_label_jobs
                WHERE workspace_id = :workspaceId ORDER BY submitted_at
                """)
                .bind("workspaceId", workspaceId)
                .map((rs, ctx) -> new JobRow(rs.getString("job_id"), rs.getString("endpoint"),
                        rs.getString("status"), rs.getInt("item_count"), rs.getInt("engine_calls")))
                .list());
    }

    private static JobRow job(List<JobRow> jobs, String endpoint) {
        return jobs.stream().filter(job -> job.endpoint().equals(endpoint)).findFirst().orElseThrow();
    }

    private long inFlightEngineCalls() {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelJobDAO.class).inFlightEngineCalls());
    }

    private Set<UUID> jobTraceIds(String workspaceId, JobRow job) {
        return statusDAO.pendingTraceTasks(workspaceId, job.jobId())
                .map(AiLabelStatus::traceId)
                .collect(Collectors.toSet())
                .block();
    }

    private List<AiLabelStatus> statuses(String workspaceId, List<UUID> traceIds) {
        return statusDAO.findLatestByTraceIds(workspaceId, Set.copyOf(traceIds)).collectList().block();
    }

    private AiLabelStatus status(String workspaceId, UUID traceId, String task) {
        return statuses(workspaceId, List.of(traceId)).stream()
                .filter(status -> status.task().equals(task))
                .findFirst()
                .orElseThrow();
    }

    private List<AiLabel> labels(String workspaceId, List<UUID> traceIds) {
        return labelsDAO.findLatestByTraceIds(workspaceId, Set.copyOf(traceIds)).collectList().block();
    }

    /** Physical rows, before any dedup: a reload writes its labels again. */
    private long labelRowCount(String workspaceId) {
        try (var statement = clickHouseConnection.createStatement();
                var result = statement.executeQuery("SELECT count() FROM %s.ai_labels WHERE workspace_id = '%s'"
                        .formatted(DATABASE_NAME, workspaceId))) {
            result.next();
            return result.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
